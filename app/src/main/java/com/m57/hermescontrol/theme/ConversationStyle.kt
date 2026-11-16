package com.m57.hermescontrol.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared conversation roles; palette, typography and motion still come from MaterialTheme. */
@Immutable
data class ConversationStyle(
    val maxWidth: Dp = 840.dp,
    val composerShape: RoundedCornerShape = RoundedCornerShape(24.dp),
    val composerMinHeight: Dp = 56.dp,
    val actionSize: Dp = 48.dp,
    val inputMaxHeight: Dp = 180.dp,
    val pendingImageSize: Dp = 88.dp,
    val messageImageMaxWidth: Dp = 320.dp,
    val messageImageMaxHeight: Dp = 360.dp,
)

val LocalConversationStyle = staticCompositionLocalOf { ConversationStyle() }
