package com.m57.hermescontrol.ui.chat

/**
 * Classifies a prompt sent while the active turn is still running.
 *
 * Separate topics must never interrupt the active run. Only an explicit
 * cancellation or explicit in-flight amendment stops or redirects it.
 */
internal enum class BusyTurnAction {
    SEPARATE,
    AMEND,
    STOP,
}

internal object BusyTurnDirective {
    private val TRAILING_PUNCTUATION = Regex("""[\s。．.！!？?、,]+$""")
    private val LEADING_PUNCTUATION = Regex("""^[\s。．.！!？?、,]+""")

    private val STOP_PHRASES =
        setOf(
            "stop",
            "cancel",
            "abort",
            "stop it",
            "cancel that",
            "stop that",
            "abort that",
            "止めて",
            "とめて",
            "やめて",
            "中止",
            "中止して",
            "中断",
            "中断して",
            "ストップ",
            "キャンセル",
            "キャンセルして",
            "さっきの止めて",
            "さっきのとめて",
            "今の止めて",
            "今のとめて",
            "それ止めて",
            "それとめて",
            "さっきの中止",
            "さっきの中止して",
            "今の中止",
            "今の中止して",
            "さっきのキャンセル",
            "今のキャンセル",
            "今のやめて",
            "さっきのやめて",
        )

    private val AMEND_PREFIXES =
        listOf(
            "今の修正",
            "今のを修正",
            "今の指示を修正",
            "今の作業を修正",
            "今の変更",
            "今の指示を変更",
            "さっきの修正",
            "さっきのを修正",
            "さっきの指示を修正",
            "さっきの件を修正",
            "さっきの変更",
            "さっきの条件を変更",
            "前の指示を修正",
            "前の指示を変更",
        )

    fun classify(
        text: String,
        explicitSteer: Boolean = false,
    ): BusyTurnAction {
        if (explicitSteer) return BusyTurnAction.AMEND
        val normalized =
            text
                .trim()
                .replace(LEADING_PUNCTUATION, "")
                .replace(TRAILING_PUNCTUATION, "")
                .lowercase()
        if (normalized.isEmpty()) return BusyTurnAction.SEPARATE
        if (normalized in STOP_PHRASES) return BusyTurnAction.STOP
        if (listOf("amend:", "redirect:", "steer:").any { normalized.startsWith(it) }) {
            return BusyTurnAction.AMEND
        }
        if (AMEND_PREFIXES.any { prefix ->
                val instruction = normalized.removePrefix(prefix).trimStart()
                normalized.startsWith(prefix) &&
                    (
                        instruction.startsWith(':') || instruction.startsWith('：') ||
                            instruction == "して" || instruction.startsWith("して、")
                    )
            }
        ) {
            return BusyTurnAction.AMEND
        }
        return BusyTurnAction.SEPARATE
    }
}
