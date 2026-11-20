package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.ui.common.BotAvatar

@Composable
internal fun MainChatHeader(
    name: String,
    avatar: BotAvatarMeta,
    connection: ConnectionStatus,
    working: Boolean,
    responseStatusUnknown: Boolean,
    onOpenDrawer: (() -> Unit)?,
    onOpenStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)
    val status =
        when {
            connection == ConnectionStatus.CONNECTING -> R.string.chat_status_connecting
            connection == ConnectionStatus.RECONNECTING -> R.string.chat_status_reconnecting
            connection != ConnectionStatus.CONNECTED -> R.string.chat_header_offline
            responseStatusUnknown -> R.string.chat_header_checking
            working -> R.string.chat_header_working
            else -> R.string.chat_header_ready
        }
    Row(Modifier.fillMaxWidth().then(modifier).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
        Box(Modifier.weight(1f)) {
            if (onOpenDrawer != null) {
                Surface(
                    onClick = onOpenDrawer,
                    shape = CircleShape,
                    color = pillColor,
                    shadowElevation = 3.dp,
                    modifier = Modifier.size(48.dp).testTag("menu_button"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.content_desc_open_drawer))
                    }
                }
            }
        }
        Column(
            modifier =
                Modifier
                    .weight(2f)
                    .testTag("chat_header_pill")
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable(role = Role.Button, onClick = onOpenStatus),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.testTag("chat_header_avatar")) {
                BotAvatar(name = name, avatar = avatar, size = 60.dp, showPresence = false)
            }
            Surface(shape = CircleShape, color = pillColor, shadowElevation = 3.dp) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
