package com.contexa.android.ui.reviews

import com.contexa.android.api.NodeType
import com.contexa.android.api.NodeView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * 검토하면서 고칠 수 있는 카드 값. server가 받는 이름과 같다
 * (decision/DecisionTask.updateFields, entity/EntityReview.updateFields).
 * 첫 칸은 카드 이름(label)으로 보이는 값이다.
 */

enum class FieldKind { TEXT, TEXTAREA, DATE, SELECT }

data class FieldOption(val value: String, val label: String)

data class FieldSpec(
    val name: String,
    val label: String,
    val kind: FieldKind,
    val options: List<FieldOption> = emptyList(),
    /** 비울 수 없는 값. 날짜는 비우면 "정하지 않음"이다. */
    val required: Boolean = false,
)

private fun options(labels: Map<String, String>) = labels.map { FieldOption(it.key, it.value) }

private val TITLE = FieldSpec("title", "제목", FieldKind.TEXT, required = true)

fun fieldsOf(type: NodeType): List<FieldSpec> = when (type) {
    NodeType.TOPIC, NodeType.DECISION_ITEM -> listOf(TITLE)
    NodeType.DECISION_EVENT -> listOf(
        FieldSpec("statement", "내용", FieldKind.TEXTAREA, required = true),
        FieldSpec("kind", "유형", FieldKind.SELECT, options(EVENT_KIND_LABEL), required = true),
        FieldSpec("occurredAt", "일어난 날", FieldKind.DATE),
    )
    NodeType.CONDITION -> listOf(FieldSpec("description", "내용", FieldKind.TEXTAREA, required = true))
    NodeType.TASK -> listOf(TITLE, FieldSpec("dueAt", "기한", FieldKind.DATE))
    NodeType.ENTITY -> listOf(
        FieldSpec("name", "이름", FieldKind.TEXT, required = true),
        FieldSpec("kind", "종류", FieldKind.SELECT, options(ENTITY_KIND_LABEL), required = true),
    )
    NodeType.SPAN, NodeType.ARTIFACT -> emptyList()
}

/** ISO 시각 → 날짜 칸의 값 "2026-12-05"(이 기기 기준 날짜). 없거나 풀 수 없으면 빈 글자. */
fun toDateInput(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    if (iso.isNullOrEmpty()) return ""
    return try {
        Instant.parse(iso).atZone(zone).toLocalDate().toString()
    } catch (_: DateTimeParseException) {
        ""
    }
}

/** 날짜 칸의 값 → 그날 0시(이 기기 기준)의 ISO 시각. */
fun fromDateInput(value: String, zone: ZoneId = ZoneId.systemDefault()): String =
    LocalDate.parse(value).atStartOfDay(zone).toInstant().toString()

/** 카드의 지금 값(칸 이름 → 값). */
fun currentValues(card: NodeView, zone: ZoneId = ZoneId.systemDefault()): Map<String, String> =
    fieldsOf(card.ref.type).mapIndexed { index, spec ->
        val raw = if (index == 0) card.label else card.states[spec.name]
        spec.name to if (spec.kind == FieldKind.DATE) toDateInput(raw, zone) else raw.orEmpty()
    }.toMap()

/** 필수인데 비어 있는 칸의 이름표. */
fun missingFields(card: NodeView, values: Map<String, String>): List<String> =
    fieldsOf(card.ref.type).filter { it.required && values[it.name].isNullOrBlank() }.map { it.label }

/**
 * 바뀐 값만 server가 받는 모양(corrections)으로 만든다.
 * 글은 앞뒤 공백을 지우고, 날짜는 그날 0시의 ISO 시각으로, 지운 날짜는 null(정하지 않음)로 보낸다.
 */
fun changedFields(
    card: NodeView,
    values: Map<String, String>,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<String, JsonElement> {
    val before = currentValues(card, zone)
    val changed = LinkedHashMap<String, JsonElement>()
    for (spec in fieldsOf(card.ref.type)) {
        val now = values[spec.name].orEmpty().trim()
        if (now == before[spec.name].orEmpty().trim()) continue
        changed[spec.name] = when {
            spec.kind != FieldKind.DATE -> JsonPrimitive(now)
            now.isEmpty() -> JsonNull
            else -> JsonPrimitive(fromDateInput(now, zone))
        }
    }
    return changed
}
