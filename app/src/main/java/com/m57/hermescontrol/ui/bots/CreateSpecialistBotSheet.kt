package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.CreateSpecialistRequest
import com.m57.hermescontrol.data.model.CreateSpecialistResponse
import com.m57.hermescontrol.data.model.SpecialistTemplate
import com.m57.hermescontrol.data.remote.SpecialistApiOutcome
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.theme.parseHexColor
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import kotlinx.coroutines.launch
import java.util.UUID

internal sealed interface CreateSpecialistSubmissionState {
    data object Idle : CreateSpecialistSubmissionState

    data object Submitting : CreateSpecialistSubmissionState

    data class Failed(
        val message: String,
    ) : CreateSpecialistSubmissionState

    data class Unsupported(
        val message: String? = null,
    ) : CreateSpecialistSubmissionState

    data class Uncertain(
        val message: String,
    ) : CreateSpecialistSubmissionState
}

internal val FALLBACK_SPECIALIST_TEMPLATES =
    listOf(
        SpecialistTemplate("blank", "白紙", "担当する役割を会話で決めます。"),
        SpecialistTemplate("research", "調査", "情報源と不確実性を明らかにして調査します。"),
        SpecialistTemplate("career", "キャリア", "本人の経験と条件を整理し、就職活動を支援します。"),
        SpecialistTemplate("development", "開発", "既存の実装と検証結果に基づいて開発を支援します。"),
        SpecialistTemplate("life", "生活", "日常の予定と必要な行動を整理します。"),
    )

private val SPECIALIST_ASCII_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

internal fun isValidSpecialistId(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.equals("default", ignoreCase = true)) return false
    return SPECIALIST_ASCII_ID_REGEX.matches(trimmed)
}

/**
 * Minimal specialist creation sheet (M5 Step 3):
 * template + ID/name + display name + avatar only. Never clones credentials or exposes API key sharing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSpecialistBotSheet(
    onDismiss: () -> Unit,
    onCreated: (CreateSpecialistResponse) -> Unit,
    repository: SpecialistsRepository = remember { SpecialistsRepository() },
) {
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf(FALLBACK_SPECIALIST_TEMPLATES) }
    var templatesLoading by remember { mutableStateOf(true) }
    var serverUnsupported by remember { mutableStateOf(false) }

    var selectedTemplateId by remember { mutableStateOf("blank") }
    var botId by remember { mutableStateOf("") }
    var displayTitle by remember { mutableStateOf("") }
    var selectedShape by remember { mutableStateOf("circle") }
    var selectedColor by remember { mutableStateOf(BOT_AVATAR_COLORS[0]) }
    var selectedIcon by remember { mutableStateOf<String?>(null) }

    // Stable UUID per user submission: reused on retry when server/network outcome is uncertain.
    var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var submission by remember { mutableStateOf<CreateSpecialistSubmissionState>(CreateSpecialistSubmissionState.Idle) }

    LaunchedEffect(Unit) {
        templatesLoading = true
        when (val res = repository.fetchTemplates()) {
            is SpecialistApiOutcome.Success -> {
                if (res.data.isNotEmpty()) {
                    templates = res.data
                    if (templates.none { it.id == selectedTemplateId }) {
                        selectedTemplateId = templates.first().id
                    }
                }
            }

            is SpecialistApiOutcome.Unsupported -> {
                serverUnsupported = true
            }

            else -> {
                Unit
            }
        }
        templatesLoading = false
    }

    fun resetSubmissionIdentityIfNeeded() {
        if (submission !is CreateSpecialistSubmissionState.Submitting) {
            requestId = UUID.randomUUID().toString()
            if (submission !is CreateSpecialistSubmissionState.Unsupported) {
                submission = CreateSpecialistSubmissionState.Idle
            }
        }
    }

    fun submitCreate(reuseRequestId: Boolean) {
        if (submission is CreateSpecialistSubmissionState.Submitting) return
        val normalizedId = botId.trim()
        val effectiveTitle = displayTitle.trim().ifBlank { normalizedId }
        if (!isValidSpecialistId(normalizedId)) {
            submission = CreateSpecialistSubmissionState.Failed("Bot IDは英数字・_・-のみで指定してください（default不可）。")
            return
        }
        val activeRequestId =
            if (reuseRequestId) {
                requestId
            } else {
                UUID.randomUUID().toString().also { requestId = it }
            }
        submission = CreateSpecialistSubmissionState.Submitting
        scope.launch {
            val outcome =
                repository.createSpecialist(
                    CreateSpecialistRequest(
                        name = normalizedId,
                        title = effectiveTitle,
                        avatar =
                            BotAvatarMeta(
                                shape = selectedShape,
                                color = selectedColor,
                                icon = selectedIcon,
                            ),
                        template_id = selectedTemplateId,
                        request_id = activeRequestId,
                    ),
                )
            when (outcome) {
                is SpecialistApiOutcome.Success -> {
                    submission = CreateSpecialistSubmissionState.Idle
                    onCreated(outcome.data)
                }

                is SpecialistApiOutcome.Unsupported -> {
                    serverUnsupported = true
                    submission = CreateSpecialistSubmissionState.Unsupported(outcome.code)
                }

                is SpecialistApiOutcome.Conflict -> {
                    submission =
                        CreateSpecialistSubmissionState.Failed(
                            when (outcome.code) {
                                "profile_already_exists" -> "同じIDのbotがすでに存在します。別のIDを指定してください。"
                                else -> outcome.message
                            },
                        )
                }

                is SpecialistApiOutcome.Validation -> {
                    submission = CreateSpecialistSubmissionState.Failed(outcome.message)
                }

                is SpecialistApiOutcome.UncertainTransport -> {
                    submission = CreateSpecialistSubmissionState.Uncertain(outcome.message)
                }
            }
        }
    }

    val isSubmitting = submission is CreateSpecialistSubmissionState.Submitting
    val isFormValid = isValidSpecialistId(botId) && !serverUnsupported && !isSubmitting

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("create_specialist_sheet"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.specialist_create_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.specialist_create_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (templatesLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (serverUnsupported || submission is CreateSpecialistSubmissionState.Unsupported) {
                Spacer(modifier = Modifier.height(10.dp))
                InlineNotice(
                    text = stringResource(R.string.specialist_create_unsupported),
                    tone = NoticeTone.Error,
                    modifier = Modifier.testTag("create_specialist_unsupported"),
                )
            }

            when (val current = submission) {
                is CreateSpecialistSubmissionState.Failed -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    InlineNotice(
                        text = current.message,
                        tone = NoticeTone.Error,
                        modifier = Modifier.testTag("create_specialist_error"),
                    )
                }

                is CreateSpecialistSubmissionState.Uncertain -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    InlineNotice(
                        text = stringResource(R.string.specialist_create_uncertain),
                        tone = NoticeTone.Warning,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { submitCreate(reuseRequestId = true) },
                        modifier = Modifier.testTag("create_specialist_uncertain"),
                    )
                }

                else -> {
                    Unit
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Avatar preview
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BotAvatar(
                    name = displayTitle.ifBlank { botId.ifBlank { "bot" } },
                    avatar =
                        BotAvatarMeta(
                            shape = selectedShape,
                            color = selectedColor,
                            icon = selectedIcon,
                        ),
                    size = 56.dp,
                    showPresence = false,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.specialist_create_template_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                templates.forEach { tpl ->
                    FilterChip(
                        selected = selectedTemplateId == tpl.id,
                        onClick = {
                            if (selectedTemplateId != tpl.id) {
                                selectedTemplateId = tpl.id
                                resetSubmissionIdentityIfNeeded()
                            }
                        },
                        enabled = !isSubmitting,
                        label = { Text(tpl.title) },
                        modifier = Modifier.testTag("specialist_template_${tpl.id}"),
                    )
                }
            }
            templates.firstOrNull { it.id == selectedTemplateId }?.let { activeTpl ->
                Text(
                    text = activeTpl.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = botId,
                onValueChange = { raw ->
                    botId = raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64)
                    resetSubmissionIdentityIfNeeded()
                },
                label = { Text(stringResource(R.string.specialist_create_name_label)) },
                placeholder = { Text(stringResource(R.string.bots_create_name_placeholder)) },
                singleLine = true,
                enabled = !isSubmitting,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("specialist_name_input"),
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = displayTitle,
                onValueChange = {
                    displayTitle = it.take(100)
                    resetSubmissionIdentityIfNeeded()
                },
                label = { Text(stringResource(R.string.specialist_create_title_label)) },
                placeholder = { Text(stringResource(R.string.bots_create_title_placeholder)) },
                singleLine = true,
                enabled = !isSubmitting,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("specialist_title_input"),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.bots_avatar_icon),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_ICONS.forEach { icon ->
                    Box(
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .clickable(enabled = !isSubmitting) {
                                    selectedIcon = icon
                                    resetSubmissionIdentityIfNeeded()
                                }.then(
                                    if (selectedIcon == icon) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ).padding(3.dp),
                    ) {
                        BotAvatar(
                            name = displayTitle.ifBlank { botId.ifBlank { "bot" } },
                            avatar = BotAvatarMeta(shape = "circle", color = selectedColor, icon = icon),
                            size = 34.dp,
                            showPresence = false,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.bots_create_avatar_shape),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_SHAPES.forEach { shape ->
                    FilterChip(
                        selected = selectedShape == shape,
                        onClick = {
                            selectedShape = shape
                            resetSubmissionIdentityIfNeeded()
                        },
                        enabled = !isSubmitting,
                        label = { Text(shape.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.bots_create_avatar_color),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_COLORS.take(5).forEach { hex ->
                    val color = parseHexColor(hex, Color.Unspecified)
                    Box(
                        modifier =
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable(enabled = !isSubmitting) {
                                    selectedColor = hex
                                    resetSubmissionIdentityIfNeeded()
                                }.then(
                                    if (selectedColor == hex) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_COLORS.drop(5).forEach { hex ->
                    val color = parseHexColor(hex, Color.Unspecified)
                    Box(
                        modifier =
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable(enabled = !isSubmitting) {
                                    selectedColor = hex
                                    resetSubmissionIdentityIfNeeded()
                                }.then(
                                    if (selectedColor == hex) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                    Text(stringResource(R.string.action_cancel))
                }
                PrimaryPillButton(
                    onClick = { submitCreate(reuseRequestId = true) },
                    enabled = isFormValid,
                    modifier = Modifier.testTag("create_specialist_submit"),
                ) {
                    Text(
                        stringResource(
                            if (isSubmitting) {
                                R.string.specialist_create_creating
                            } else {
                                R.string.specialist_create_submit
                            },
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
