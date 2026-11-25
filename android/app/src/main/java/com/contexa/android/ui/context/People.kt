package com.contexa.android.ui.context

import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Fact
import com.contexa.android.api.NameCard
import com.contexa.android.api.NameForm
import com.contexa.android.api.NodeType
import com.contexa.android.api.OpenIssueKind
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Source
import com.contexa.android.ui.documents.LANGUAGES
import com.contexa.android.ui.documents.languageLabel
import java.text.Normalizer
import java.util.Locale

/* "사람" 화면의 규칙: 인물·기관 카드, 언어별 표기, 표기가 나온 발언, 이름이 겹치는 다른 인물. 화면과 떼어 두고 테스트한다. */

/** 사람 목록의 한 줄. 확인된 표기는 server가 states에 "name.언어"로 실어 준다(언어마다 먼저 확인된 것 하나). */
data class PersonRow(val entity: Fact) {
    val organization: Boolean get() = entity.states["kind"] == "ORGANIZATION"

    /** 확인된 표기. 대표 표기와 같은 것은 뺀다. */
    val confirmedNames: List<String>
        get() = LANGUAGES.mapNotNull { entity.states["name.${it.code}"] }.filter { it != entity.label }.distinct()
}

/** 현황에 실린 인물·기관 가운데 확인된 것. 사람 먼저, 기관은 뒤에. AI 후보 인물은 검토함에서 다룬다. */
fun personRows(packet: ContextPacket): List<PersonRow> =
    packet.facts
        .filter { it.node.type == NodeType.ENTITY && it.status == ReviewStatus.CONFIRMED }
        .map(::PersonRow)
        .sortedBy { it.organization }

/** 한 언어의 표기들. */
data class NameGroup(val lang: String, val label: String, val forms: List<NameForm>)

private val NAME_ORDER = listOf("CONFIRMED", "CANDIDATE", "REJECTED")

/** 표기를 언어별로 묶는다. 언어는 고를 수 있는 언어 순서, 그 밖의 언어는 뒤에. 한 언어 안에서는 확인 → 제안 → 거절. */
fun nameGroups(names: List<NameForm>): List<NameGroup> {
    val order = LANGUAGES.map { it.code }
    return names.groupBy { it.lang }
        .entries
        .sortedBy { (lang, _) -> order.indexOf(lang).let { if (it < 0) order.size else it } }
        .map { (lang, forms) -> NameGroup(lang, languageLabel(lang), forms.sortedBy { NAME_ORDER.indexOf(it.status) }) }
}

fun nameStatusLabel(status: String): String = when (status) {
    "CONFIRMED" -> "확인됨"
    "CANDIDATE" -> "제안"
    "REJECTED" -> "거절됨"
    else -> status
}

/** 표기 하나에 할 수 있는 일: 제안은 확인·거절, 확인된 것은 거절, 거절된 것은 다시 확인. */
enum class NameAction { CONFIRM, REJECT }

fun nameActions(status: String): List<NameAction> = when (status) {
    "CANDIDATE" -> listOf(NameAction.CONFIRM, NameAction.REJECT)
    "CONFIRMED" -> listOf(NameAction.REJECT)
    "REJECTED" -> listOf(NameAction.CONFIRM)
    else -> emptyList()
}

/**
 * 매칭용 정규화. 원문은 바꾸지 않고 비교할 때만 쓴다.
 * NFC, 소문자, 공백 하나로. 아랍어는 모음 기호(U+064B–U+0652)와 타트윌(U+0640)을 빼고 알리프 변형(أ إ آ)을 ا로.
 */
fun normalizeForMatch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace(Regex("[\\u0623\\u0625\\u0622]"), "ا")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * 이 표기가 나온 발언. 표기에 근거 발언이 적혀 있으면 그것을, 아니면 표기가 글자 그대로 들어 있는 발언을 고른다.
 * 비슷한 음역(Ahmed/Ahmad)은 같은 표기로 치지 않는다.
 */
fun sourcesFor(form: NameForm, sources: List<Source>): List<Source> {
    form.evidenceSpanId?.let { id -> sources.find { it.span.id == id }?.let { return listOf(it) } }
    val needle = normalizeForMatch(form.form)
    if (needle.isEmpty()) return emptyList()
    return sources.filter { normalizeForMatch(it.text).contains(needle) }
}

/** 이름 화면의 원문 배치: 표기마다 그 표기가 나온 첫 발언, 그리고 어느 표기에도 걸리지 않은 나머지 언급. */
data class NameEvidence(val byForm: Map<NameForm, Source>, val others: List<Source>)

fun nameEvidence(card: NameCard): NameEvidence {
    val byForm = card.names.mapNotNull { form -> sourcesFor(form, card.sources).firstOrNull()?.let { form to it } }.toMap()
    val shown = byForm.values.map { it.span.id }.toSet()
    return NameEvidence(byForm, card.sources.filter { it.span.id !in shown })
}

/** 이름이 겹치는 다른 인물·기관을 찾을 표기: 대표 표기와 거절되지 않은 표기. 같은 글자는 한 번만. */
fun searchSurfaces(card: NameCard): List<String> =
    (listOf(card.entity.label) + card.names.filter { it.status != "REJECTED" }.map { it.form })
        .filter { it.isNotBlank() }
        .distinctBy(::normalizeForMatch)

/**
 * 이름이 겹치는 다른 인물·기관 하나.
 *
 * @param via       겹친 표기(이 인물의 표기 가운데 찾을 때 쓴 것)
 * @param ambiguous server가 "누구인지 불분명한 이름"(AMBIGUOUS_NAME)으로 알렸나
 */
data class SimilarPerson(val entity: Fact, val via: String, val ambiguous: Boolean)

/**
 * 표기마다 찾은 결과(surface → 답변서)를 모아, 나 자신을 뺀 다른 인물·기관을 한 번씩만 돌려준다.
 * 같은 사람인지는 사람이 판단한다. 여기서 합치지 않는다.
 */
fun similarPeople(selfId: String, found: List<Pair<String, ContextPacket>>): List<SimilarPerson> {
    val seen = LinkedHashMap<String, SimilarPerson>()
    for ((surface, packet) in found) {
        val flagged = packet.open.filter { it.kind == OpenIssueKind.AMBIGUOUS_NAME }.mapNotNull { it.node?.id }.toSet()
        packet.facts
            .filter { it.node.type == NodeType.ENTITY && it.node.id != selfId }
            .forEach { fact -> seen.putIfAbsent(fact.node.id, SimilarPerson(fact, surface, fact.node.id in flagged)) }
    }
    return seen.values.toList()
}

fun nameChangedMessage(action: NameAction, impacts: Int): String {
    val base = if (action == NameAction.CONFIRM) "표기를 확인했습니다." else "표기를 거절했습니다."
    return if (impacts > 0) "$base 이 인물에 기대는 카드 ${impacts}개가 영향을 받습니다." else base
}
