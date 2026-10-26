package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

@Composable
internal fun AttachmentPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(240.dp).testTag("chat_attachment_menu"),
    ) {
        AttachmentPickerItem(R.string.chat_attachment_photo, R.drawable.ic_attach_photo_symbol, "photo", onPhoto)
        AttachmentPickerItem(R.string.chat_attachment_camera, R.drawable.ic_attach_camera_symbol, "camera", onCamera)
        AttachmentPickerItem(R.string.chat_attachment_file, R.drawable.ic_attach_file_symbol, "file", onFile)
    }
}

@Composable
private fun AttachmentPickerItem(
    label: Int,
    icon: Int,
    tag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp).testTag("chat_attachment_" + tag),
    )
}
