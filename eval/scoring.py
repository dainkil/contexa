"""뽑기 채점: 모델이 낸 카드·줄 후보를 회의 하나의 정답 라벨과 맞춰 본다.

맞추는 기준
- 카드: 종류가 같고 근거 발언이 하나 이상 겹치면 같은 카드로 본다(인물·기관은 이름 표기로 맞춘다).
  여러 개가 겹치면 필수 정답 → 사건 종류가 맞는 것 → 근거가 더 많이 겹치는 것 순서로 하나씩 짝짓는다.
- 줄: 양 끝이 짝지어진 정답 카드(또는 이미 있는 카드, 발언)를 가리키고 종류까지 같아야 맞다.
- optional로 표시된 정답(해석이 갈릴 수 있는 것)은 맞혀도 세지 않고, 놓쳐도 틀리지 않는다.
  optional 카드에 걸린 줄도 optional이다.

글자가 달라도 근거가 같으면 같은 카드로 보므로, 글이 얼마나 잘 쓰였는지는 재지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .common import normalize, ratio

# 채점하는 줄. 빼는 것: EVIDENCED_BY·MENTIONS(카드의 근거로 따로 잰다), SPOKEN_BY(화자 표기에서 바로 나온다),
# DERIVED_FROM(어느 결정에서 나온 일인지는 해석이 갈린다)
SCORED_RELS = ("HAS_ITEM", "HAS_EVENT", "SUPERSEDES", "WITHDRAWS", "REQUIRES", "SATISFIED_BY", "ASSIGNED_TO",
               "CONFLICTS_WITH")
CARD_TYPES = ("TOPIC", "DECISION_ITEM", "DECISION_EVENT", "CONDITION", "TASK", "ENTITY")
EVIDENCE_TYPES = {"DECISION_EVENT", "CONDITION", "TASK"}
DECIDED = {"DECISION", "CHANGE"}


@dataclass(frozen=True)
class Predicted:
    key: str
    type: str
    text: str
    kind: str | None
    evidence: frozenset[int]


def label_of(card: dict[str, Any]) -> str:
    return card["text"] if "text" in card else card["names"][0]


def existing_cards(meetings: list[dict[str, Any]], index: int) -> list[dict[str, Any]]:
    """index번째 회의를 뽑을 때 이미 있는 카드: 앞 회의의 필수 정답과 carry 표시된 카드(사람이 확인했다고 본다)."""
    return [card for meeting in meetings[:index] for card in meeting["cards"]
            if not card.get("optional") or card.get("carry")]


def state_of(existing: list[dict[str, Any]]) -> dict[str, Any]:
    """일꾼이 서버에서 받는 context/state 모양. 카드 id는 정답 id를 그대로 쓴다."""
    facts = []
    for card in existing:
        fact: dict[str, Any] = {"node": {"id": card["id"], "type": card["type"]}, "label": label_of(card),
                                "status": "CONFIRMED", "states": {}}
        if card["type"] == "DECISION_EVENT":
            fact["states"]["kind"] = card["kinds"][0]
        facts.append(fact)
    return {"facts": facts}


def spans_of(document: Any) -> list[dict[str, Any]]:
    """일꾼이 서버에서 받는 발언 모양. 발언 id는 "m1:3" 같은 평가용 id다."""
    return [{"seq": span.seq, "speakerLabel": span.speaker, "text": span.text, "ref": {"id": span.id, "type": "SPAN"}}
            for span in document.spans]


def oracle_output(meeting: dict[str, Any], existing: list[dict[str, Any]]) -> dict[str, Any]:
    """정답을 모델의 답 모양으로 바꾼다. 채점과 라벨이 맞물리는지 비용 없이 확인할 때 쓴다."""
    xlabel = {card["id"]: f"x{index}" for index, card in enumerate(existing, start=1)}

    def name(ref: str) -> str:
        if ref in xlabel:
            return xlabel[ref]
        if ":" in ref:
            return "s" + ref.split(":")[1]
        return ref

    cards = [{"key": card["id"], "type": card["type"], "text": label_of(card),
              "event_kind": card["kinds"][0] if card["type"] == "DECISION_EVENT" else "NONE",
              "entity_kind": card["kind"] if card["type"] == "ENTITY" else "NONE",
              "occurred_at": "", "evidence": card["evidence"]} for card in meeting["cards"]]
    links = [{"rel": link["rel"], "from": name(link["from"]), "to": name(link["to"]), "evidence": link["evidence"]}
             for link in meeting["links"]]
    return {"cards": cards, "links": links}


def predicted_cards(output: dict[str, Any], extraction: Any, span_count: int) -> list[Predicted]:
    """일꾼이 걸러 낸 뒤 남은 카드. 근거는 모델이 댄 발언 번호(없는 번호는 뺀다)."""
    raw: dict[str, dict[str, Any]] = {}
    for card in output.get("cards", []):
        raw.setdefault(card.get("key"), card)
    cards = []
    for node in extraction.candidates["nodes"]:
        fields = node["fields"]
        text = fields.get("title") or fields.get("description") or fields.get("statement") or fields.get("name") or ""
        evidence = frozenset(n for n in raw.get(node["key"], {}).get("evidence", [])
                             if isinstance(n, int) and 1 <= n <= span_count)
        cards.append(Predicted(node["key"], node["type"], text, fields.get("kind"), evidence))
    return cards


def _match(predicted: list[Predicted], gold: list[dict[str, Any]]) -> dict[str, str]:
    """예측 key → 정답 id. 하나씩만 짝짓는다."""
    pairs = []
    for p in predicted:
        for g in gold:
            if g["type"] != p.type:
                continue
            if p.type == "ENTITY":
                if normalize(p.text) not in {normalize(name) for name in g["names"]}:
                    continue
                overlap = 1.0
            else:
                gold_evidence = set(g["evidence"])
                if not p.evidence & gold_evidence:
                    continue
                overlap = len(p.evidence & gold_evidence) / len(p.evidence | gold_evidence)
            kind_ok = p.type != "DECISION_EVENT" or p.kind in g["kinds"]
            pairs.append(((not g.get("optional"), kind_ok, overlap), p.key, g["id"]))
    pairs.sort(key=lambda pair: pair[0], reverse=True)
    matched: dict[str, str] = {}
    taken: set[str] = set()
    for _, key, gold_id in pairs:
        if key not in matched and gold_id not in taken:
            matched[key] = gold_id
            taken.add(gold_id)
    return matched


def _is_duplicate(p: Predicted, existing: list[dict[str, Any]]) -> bool:
    """이미 있는 카드를 새로 만들었나. 인물·기관은 이름 표기, 안건·결정 항목은 글자가 같을 때만 센다(적게 센다)."""
    if p.type == "ENTITY":
        return any(card["type"] == "ENTITY" and normalize(p.text) in {normalize(n) for n in card["names"]}
                   for card in existing)
    if p.type in ("TOPIC", "DECISION_ITEM"):
        return any(card["type"] == p.type and normalize(p.text) == normalize(card["text"]) for card in existing)
    return False


def _counts() -> dict[str, int]:
    return {"tp": 0, "fp": 0, "fn": 0}


def score_meeting(meeting: dict[str, Any], existing: list[dict[str, Any]], output: dict[str, Any],
                  extraction: Any, span_count: int) -> dict[str, Any]:
    gold = meeting["cards"]
    gold_by_id = {card["id"]: card for card in gold}
    optional = {card["id"] for card in gold if card.get("optional")}
    existing_ids = {card["id"] for card in existing}
    predicted = predicted_cards(output, extraction, span_count)
    matched = _match(predicted, gold)

    cards = {card_type: _counts() for card_type in CARD_TYPES}
    extra, missed = [], []
    kind = {"correct": 0, "total": 0}
    evidence = {"correct": 0, "total": 0}
    duplicates = []
    for p in predicted:
        gold_id = matched.get(p.key)
        if gold_id is None:
            cards[p.type]["fp"] += 1
            extra.append(f"[{p.type}{'/' + p.kind if p.kind else ''}] {p.text} {sorted(p.evidence)}")
            if _is_duplicate(p, existing):
                duplicates.append(f"[{p.type}] {p.text}")
            continue
        if gold_id in optional:
            continue
        cards[p.type]["tp"] += 1
        g = gold_by_id[gold_id]
        if p.type == "DECISION_EVENT":
            kind["total"] += 1
            kind["correct"] += p.kind in g["kinds"]
        if p.type in EVIDENCE_TYPES:
            evidence["total"] += 1
            evidence["correct"] += bool(p.evidence) and p.evidence <= set(g["evidence"])
    for g in gold:
        if g["id"] not in optional and g["id"] not in matched.values():
            cards[g["type"]]["fn"] += 1
            missed.append(f"[{g['type']}] {label_of(g)}")

    not_decided = set(meeting.get("not_decided", []))
    misclassified = [f"{p.kind}: {p.text} {sorted(p.evidence)}" for p in predicted
                     if p.type == "DECISION_EVENT" and p.kind in DECIDED and p.evidence and p.evidence <= not_decided]

    # ---- 줄 ----
    def link_optional(link: dict[str, Any]) -> bool:
        return bool(link.get("optional")) or link["from"] in optional or link["to"] in optional

    required = {(l["rel"], l["from"], l["to"]) for l in meeting["links"] if not link_optional(l)}
    allowed = {(l["rel"], l["from"], l["to"]) for l in meeting["links"] if link_optional(l)}
    links = {rel: _counts() for rel in SCORED_RELS}
    cross = _counts()
    found: set[tuple[str, str, str]] = set()

    def end(edge: dict[str, Any], side: str) -> str | None:
        if f"{side}Key" in edge:
            return matched.get(edge[f"{side}Key"])
        return edge[f"{side}Ref"]["id"]

    for edge in extraction.candidates["edges"]:
        rel = edge["rel"]
        if rel not in SCORED_RELS:
            continue
        src, dst = end(edge, "src"), end(edge, "dst")
        identity = (rel, src, dst)
        if identity in found or identity in allowed:
            continue
        is_cross = src in existing_ids or dst in existing_ids
        if identity in required:
            found.add(identity)
            links[rel]["tp"] += 1
            cross["tp"] += is_cross
        else:
            links[rel]["fp"] += 1
            cross["fp"] += is_cross
            extra.append(f"줄 {rel}: {src or '(짝 없는 카드)'} → {dst or '(짝 없는 카드)'}")
    for identity in required - found:
        links[identity[0]]["fn"] += 1
        cross["fn"] += identity[1] in existing_ids or identity[2] in existing_ids
        missed.append(f"줄 {identity[0]}: {identity[1]} → {identity[2]}")

    return {
        "document": meeting["document"],
        "cards": cards,
        "links": links,
        "cross_links": cross,
        "event_kind": kind,
        "evidence": evidence,
        "misclassified": misclassified,
        "duplicates": duplicates,
        "dropped": list(extraction.dropped),
        "extra": extra,
        "missed": missed,
    }


def _prf(counts: dict[str, int]) -> dict[str, Any]:
    return {**counts, "precision": ratio(counts["tp"], counts["tp"] + counts["fp"]),
            "recall": ratio(counts["tp"], counts["tp"] + counts["fn"])}


def _add(total: dict[str, int], counts: dict[str, int]) -> None:
    for name, value in counts.items():
        total[name] = total.get(name, 0) + value


def summarize(meetings: list[dict[str, Any]]) -> dict[str, Any]:
    cards = {card_type: _counts() for card_type in CARD_TYPES}
    links = {rel: _counts() for rel in SCORED_RELS}
    all_cards, all_links, cross = _counts(), _counts(), _counts()
    kind = {"correct": 0, "total": 0}
    evidence = {"correct": 0, "total": 0}
    for meeting in meetings:
        for card_type, counts in meeting["cards"].items():
            _add(cards[card_type], counts)
            _add(all_cards, counts)
        for rel, counts in meeting["links"].items():
            _add(links[rel], counts)
            _add(all_links, counts)
        _add(cross, meeting["cross_links"])
        _add(kind, meeting["event_kind"])
        _add(evidence, meeting["evidence"])
    return {
        "cards": {"ALL": _prf(all_cards), **{t: _prf(c) for t, c in cards.items()}},
        "links": {"ALL": _prf(all_links), **{r: _prf(c) for r, c in links.items() if any(c.values())}},
        "cross_links": _prf(cross),
        "event_kind_accuracy": {**kind, "rate": ratio(kind["correct"], kind["total"])},
        "evidence_exact": {**evidence, "rate": ratio(evidence["correct"], evidence["total"])},
        "misclassified_as_decided": sum(len(m["misclassified"]) for m in meetings),
        "duplicates": sum(len(m["duplicates"]) for m in meetings),
        "dropped_by_worker": sum(len(m["dropped"]) for m in meetings),
    }
