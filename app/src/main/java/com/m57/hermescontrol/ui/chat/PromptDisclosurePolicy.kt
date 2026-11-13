package com.m57.hermescontrol.ui.chat

/** Presentation-only decisions. The complete message always remains untouched. */
internal object PromptDisclosurePolicy {
    const val COLLAPSED_LINE_LIMIT = 12

    fun shouldCollapse(
        role: MessageRole,
        overflows: Boolean,
        manuallyExpanded: Boolean,
        currentSearchMatch: Boolean,
    ): Boolean = role == MessageRole.USER && overflows && !manuallyExpanded && !currentSearchMatch

    fun showControl(
        role: MessageRole,
        overflows: Boolean,
        currentSearchMatch: Boolean,
    ): Boolean = role == MessageRole.USER && overflows && !currentSearchMatch
}
