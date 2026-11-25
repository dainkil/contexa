package com.contexa.android.ui.reviews

import com.contexa.android.api.Edge
import com.contexa.android.api.Impact
import com.contexa.android.api.ReviewActionRequest
import com.contexa.android.api.ReviewItem
import com.contexa.android.api.ReviewState
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Verdict
import com.contexa.android.api.contexaJson
import kotlinx.serialization.json.JsonElement

/*
 * 검토 판단을 server 요청(ActionRequest)으로 만드는 규칙. 화면과 떼어 두고 테스트한다.
 * 상태 규칙은 server contract/ReviewStatus.canMoveTo, review/ReviewImpact.followCard와 같다.
 *
 * 줄 하나에 대한 고름은 Verdict?로 적는다. null = 따로 정하지 않음(카드 판단이나 묶음 버튼을 따른다).
 */

fun verdictLabel(verdict: Verdict): String = when (verdict) {
    Verdict.CONFIRM -> "확인"
    Verdict.HOLD -> "보류"
    Verdict.REJECT -> "거절"
    Verdict.RETRACT -> "철회"
}

fun isPending(status: ReviewStatus): Boolean = status == ReviewStatus.CANDIDATE || status == ReviewStatus.HELD

/** 이 상태에 이 판단을 내릴 수 있나. */
fun canApply(verdict: Verdict, status: ReviewStatus): Boolean = when (verdict) {
    Verdict.CONFIRM, Verdict.REJECT -> isPending(status)
    Verdict.HOLD -> status == ReviewStatus.CANDIDATE
    Verdict.RETRACT -> status == ReviewStatus.CONFIRMED
}

/**
 * 묶음 아래에 놓을 판단 버튼.
 * 카드가 있으면 카드 상태로 정한다. 이미 확인된 카드의 묶음이 열려 있으면 새로 들어온 줄이 있다는 뜻이라
 * "확인"(카드는 그대로, 후보 줄을 확인)과 "철회"를 둔다.
 * 카드가 없는 발언 묶음은 줄 상태로 정하고, 판단은 따로 고르지 않은 줄 모두에 적용한다.
 */
fun bundleVerdicts(item: ReviewItem): List<Verdict> {
    val card = item.card
    if (card != null) {
        return when (card.status) {
            ReviewStatus.CANDIDATE -> listOf(Verdict.CONFIRM, Verdict.HOLD, Verdict.REJECT)
            ReviewStatus.HELD -> listOf(Verdict.CONFIRM, Verdict.REJECT)
            ReviewStatus.CONFIRMED ->
                if (item.state == ReviewState.OPEN) listOf(Verdict.CONFIRM, Verdict.RETRACT) else listOf(Verdict.RETRACT)
            else -> emptyList()
        }
    }
    return Verdict.entries.filter { verdict -> item.edges.any { canApply(verdict, it.status) } }
}

/** 줄마다 고를 수 있는 것. 첫 값(null = 따름)이 기본이다. */
fun edgeChoices(edge: Edge): List<Verdict?> = listOf<Verdict?>(null) + Verdict.entries.filter { canApply(it, edge.status) }

/** 따로 판단할 수 있는 줄. 이미 거절·철회된 줄은 고를 것이 없어 뺀다. */
fun decidableEdges(item: ReviewItem): List<Edge> = item.edges.filter { edgeChoices(it).size > 1 }

/** "따름"의 이름. 카드가 있으면 카드 판단을, 없으면(발언 묶음) 아래 버튼을 따른다. */
fun followLabel(item: ReviewItem): String = if (item.card != null) "카드를 따름" else "버튼대로"

/** 줄 하나에 고른 것의 이름. */
fun edgeChoiceLabel(choice: Verdict?, follow: String): String = if (choice == null) follow else "이 줄만 ${verdictLabel(choice)}"

const val CONFIRM_SWEEP_NOTE = "확인하면 따로 고르지 않은 후보 줄도 함께 확인됩니다. 틀린 줄은 줄마다 '이 줄만 거절'을 고르세요."

/**
 * "확인"을 누르면 따로 고르지 않은 후보 줄까지 함께 확인되는가(server ReviewImpact.followCard).
 * 그렇다면 버튼 위에 알려서, 틀린 줄을 모르고 확인하지 않게 한다.
 */
fun confirmSweepsEdges(item: ReviewItem, choices: Map<String, Verdict>): Boolean =
    Verdict.CONFIRM in bundleVerdicts(item) &&
        item.edges.any { isPending(it.status) && choices[it.id] == null }

/** 검토하면서 카드 값을 고칠 수 있는가. 거절·철회된 카드는 고치지 않는다. */
fun canCorrect(item: ReviewItem): Boolean {
    val card = item.card ?: return false
    return fieldsOf(card.ref.type).isNotEmpty() && (isPending(card.status) || card.status == ReviewStatus.CONFIRMED)
}

/**
 * 판단 요청을 만든다. 보낼 것이 없으면 null.
 *
 * @param verdict     묶음 버튼으로 내린 판단. 고친 내용만 저장할 때는 null
 * @param choices     줄마다 따로 고른 판단(줄 id → 판단)
 * @param corrections 바뀐 카드 값(changedFields)
 */
fun buildAction(
    item: ReviewItem,
    verdict: Verdict?,
    choices: Map<String, Verdict>,
    corrections: Map<String, JsonElement> = emptyMap(),
): ReviewActionRequest? {
    val edges = LinkedHashMap<String, Verdict>()
    for (edge in item.edges) {
        val choice = choices[edge.id]
        if (choice != null) {
            edges[edge.id] = choice
        } else if (item.card == null && verdict != null && canApply(verdict, edge.status)) {
            // 카드가 없으면 server가 따라갈 카드 판단이 없으니 줄마다 적어 보낸다
            edges[edge.id] = verdict
        }
    }

    val body = ReviewActionRequest(
        card = verdict.takeIf { item.card != null },
        corrections = corrections.takeIf { item.card != null && it.isNotEmpty() },
        edges = edges.takeIf { it.isNotEmpty() },
        expectedVersion = item.version,
        idempotencyKey = "",
    )
    if (body.card == null && body.corrections == null && body.edges == null) return null

    // 같은 판에 같은 요청을 두 번 보내면(두 번 누름, 다시 보내기) server가 한 번만 처리한다.
    // 다른 요청은 다른 키가 되고, 그사이 묶음이 바뀌었으면 server가 409로 막는다.
    val key = "${item.id}:${item.version}:${hash(contexaJson.encodeToString(ReviewActionRequest.serializer(), body))}"
    return body.copy(idempotencyKey = key)
}

/** FNV-1a 32비트. 요청 내용을 짧은 키로 줄이는 데만 쓴다. */
private fun hash(text: String): String {
    var h = 0x811c9dc5.toInt()
    for (c in text) {
        h = (h xor c.code) * 0x01000193
    }
    return h.toUInt().toString(36)
}

/**
 * 처리한 뒤 알림 문장. 영향받는 카드 수를 덧붙인다.
 * 그 가운데 확인된 것이 바뀌어 다시 봐야 하는 카드는 server가 검토함 "다시 확인"에 올린다.
 */
fun resultMessage(verdict: Verdict?, corrected: Boolean, impacts: List<Impact>): String {
    val done = when {
        verdict == Verdict.CONFIRM -> if (corrected) "고쳐서 확인했습니다." else "확인했습니다."
        verdict != null -> "${verdictLabel(verdict)}했습니다."
        corrected -> "고친 내용을 저장했습니다."
        else -> "줄 판단을 반영했습니다."
    }
    val affected = impacts.map { it.affected.id }.toSet().size
    return if (affected > 0) "$done 이 변경에 기대는 카드 ${affected}개가 영향을 받습니다." else done
}
