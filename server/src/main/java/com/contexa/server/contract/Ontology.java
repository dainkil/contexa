package com.contexa.server.contract;

import static com.contexa.server.contract.NodeType.ARTIFACT;
import static com.contexa.server.contract.NodeType.CONDITION;
import static com.contexa.server.contract.NodeType.DECISION_EVENT;
import static com.contexa.server.contract.NodeType.DECISION_ITEM;
import static com.contexa.server.contract.NodeType.ENTITY;
import static com.contexa.server.contract.NodeType.SPAN;
import static com.contexa.server.contract.NodeType.TASK;
import static com.contexa.server.contract.NodeType.TOPIC;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 결정 온톨로지: 어떤 카드에서 어떤 카드로 어떤 줄을 그을 수 있는지 정한다. */
public final class Ontology {

	public static final String VERSION = "v1";

	/** 허용 조합 한 묶음: 이 출발 카드들에서 이 도착 카드들로 그을 수 있다. */
	private record Rule(Set<NodeType> sources, Set<NodeType> targets) {
	}

	/** 줄 종류마다 허용 묶음이 하나 이상 있다. 묶음 중 하나에만 맞으면 허용한다. */
	private static final Map<RelType, List<Rule>> RULES = new EnumMap<>(RelType.class);

	static {
		allow(RelType.HAS_ITEM, EnumSet.of(TOPIC), EnumSet.of(DECISION_ITEM));
		allow(RelType.HAS_EVENT, EnumSet.of(DECISION_ITEM), EnumSet.of(DECISION_EVENT));
		allow(RelType.SUPERSEDES, EnumSet.of(DECISION_EVENT), EnumSet.of(DECISION_EVENT));
		allow(RelType.WITHDRAWS, EnumSet.of(DECISION_EVENT), EnumSet.of(DECISION_EVENT));
		allow(RelType.REQUIRES, EnumSet.of(DECISION_ITEM, TASK), EnumSet.of(CONDITION));
		// 할 일끼리의 선후 관계: "번역이 끝나야 인쇄 발주".
		// 결정 항목 → 할 일은 허용하지 않는다. 할 일 -DERIVED_FROM-> 결정 항목과 서로 맞물려 돌기 때문이다.
		allow(RelType.REQUIRES, EnumSet.of(TASK), EnumSet.of(TASK));
		// 조건은 발언으로 충족되거나(누가 "승인 났다"고 말함), 할 일이 끝나서 충족된다.
		allow(RelType.SATISFIED_BY, EnumSet.of(CONDITION), EnumSet.of(SPAN, TASK));
		allow(RelType.DERIVED_FROM, EnumSet.of(TASK), EnumSet.of(DECISION_ITEM));
		allow(RelType.ASSIGNED_TO, EnumSet.of(TASK), EnumSet.of(ENTITY));
		allow(RelType.EVIDENCED_BY, EnumSet.of(DECISION_EVENT, CONDITION, TASK), EnumSet.of(SPAN));
		allow(RelType.SPOKEN_BY, EnumSet.of(SPAN), EnumSet.of(ENTITY));
		allow(RelType.MENTIONS, EnumSet.of(SPAN), EnumSet.of(ENTITY));
		allow(RelType.USES, EnumSet.of(ARTIFACT), EnumSet.complementOf(EnumSet.of(ARTIFACT)));
		allow(RelType.CONFLICTS_WITH, EnumSet.of(DECISION_EVENT), EnumSet.of(DECISION_EVENT, SPAN));
	}

	private Ontology() {
	}

	private static void allow(RelType rel, Set<NodeType> sources, Set<NodeType> targets) {
		RULES.computeIfAbsent(rel, r -> new ArrayList<>()).add(new Rule(sources, targets));
	}

	/** 허용 조합 한 줄: 이 줄은 이 출발 카드들에서 이 도착 카드들로 그을 수 있다. */
	public record Allowed(RelType rel, Set<NodeType> sources, Set<NodeType> targets) {
	}

	/**
	 * 규칙표 전체. AI 인턴이 "어떤 줄을 낼 수 있는지"를 여기서 읽어 간다.
	 * 규칙을 Python 쪽에 따로 적어 두지 않기 위해서다. 두 곳에 적으면 언젠가 어긋난다.
	 */
	public static List<Allowed> rules() {
		List<Allowed> all = new ArrayList<>();
		RULES.forEach((rel, rules) -> rules.forEach(
				rule -> all.add(new Allowed(rel, Set.copyOf(rule.sources()), Set.copyOf(rule.targets())))));
		return all;
	}

	public static boolean isAllowed(RelType rel, NodeType src, NodeType dst) {
		if (rel == null || src == null || dst == null) {
			return false;
		}
		for (Rule rule : RULES.getOrDefault(rel, List.of())) {
			if (rule.sources().contains(src) && rule.targets().contains(dst)) {
				return true;
			}
		}
		return false;
	}
}
