package com.m57.hermescontrol.career

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class CareerAnswerDraft(
    val body: String = "",
    val baseBody: String = "",
    val documentId: String? = null,
    val documentVersion: Int = 0,
    val entityVersion: Int = 0,
    val questionVersion: String = "",
    val questionText: String = "",
) {
    val changed: Boolean get() = body != baseBody
}

@Serializable
data class CareerEsCache(
    val drafts: Map<String, CareerAnswerDraft> = emptyMap(),
    val lastQuestion: String? = null,
    val pending: CareerPendingOperation? = null,
)

data class CareerEsState(
    val initialized: Boolean = false,
    val loading: Boolean = false,
    val stale: Boolean = true,
    val detail: CareerCompanyDetail? = null,
    val selected: String? = null,
    val editingLoading: Boolean = false,
    val busy: Boolean = false,
    val localSaved: Boolean = false,
    val cache: CareerEsCache = CareerEsCache(),
    val error: String? = null,
    val message: String? = null,
)

internal fun CareerEntity.questions(): List<JsonObject> =
    (data["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

internal fun CareerCompanyDetail.answerWork(
    offerId: String,
    questionId: String,
): JsonObject = (answer_states[offerId] as? JsonObject)?.get(questionId) as? JsonObject ?: JsonObject(emptyMap())

internal fun CareerEntity.answersQuestion(
    offerId: String,
    questionId: String,
): Boolean {
    val context = data["career_context"] as? JsonObject ?: return false
    return context.text("offer_id") == offerId &&
        (context["question_refs"] as? JsonArray).orEmpty().singleOrNull().let {
            (it as? JsonObject)?.text("id") == questionId
        }
}

internal fun questionTitle(question: JsonObject): String {
    val text = question.text("text").orEmpty()
    return when {
        "習得語学" in text -> {
            "語学"
        }

        "資格・免許" in text -> {
            "資格・免許"
        }

        "高校にて" in text -> {
            "高校の部活・役職"
        }

        "クラブ・サークル" in text -> {
            "大学の部活・役職"
        }

        "アルバイト" in text -> {
            "アルバイト・役職"
        }

        "選社軸" in text -> {
            "選社軸と理由"
        }

        "第一志望" in text -> {
            "興味のある業界・会社（第一志望）"
        }

        "第二志望" in text -> {
            "興味のある業界・会社（第二志望）"
        }

        "第三志望" in text -> {
            "興味のある業界・会社（第三志望）"
        }

        question.text("input_kind") == "file" -> {
            "写真の添付"
        }

        else -> {
            text
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.matches(Regex("設問\\s*\\d+|必須|任意")) }
                .lastOrNull()
                ?.take(48) ?: "原文を確認"
        }
    }
}

internal fun questionGroup(question: JsonObject): String =
    when {
        question.text("input_kind") in setOf("file", "choice") -> "マイページでの対応"
        questionTitle(question) in setOf("語学", "資格・免許", "高校の部活・役職", "大学の部活・役職", "アルバイト・役職") -> "基本情報"
        "志望" in questionTitle(question) || "選社軸" in questionTitle(question) -> "興味・選社軸"
        else -> "記述回答"
    }

internal fun answerConstraint(
    question: JsonObject,
    body: String,
): String? {
    val maximum = question.text("max_characters")?.toIntOrNull()
    val count = body.codePointCount(0, body.length)
    return when {
        maximum != null && count > maximum -> "${count - maximum}字超過しています"

        count > 20000 -> "保存上限の20,000字を${count - 20000}字超過しています"

        (question.text("no_line_breaks") == "true" || "改行を使用せず" in question.text("text").orEmpty()) &&
            ('\n' in body || '\r' in body) -> "改行を取り除いてください"

        else -> null
    }
}

internal fun careerDisplayTime(
    value: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
    }.getOrElse {
        value.replace('T', ' ').replace(Regex("(\\d{2}:\\d{2}):\\d{2}(?:\\.\\d+)?"), "$1")
    }
