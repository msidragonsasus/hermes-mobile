package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic credentials only. Initial RED for the frozen persistence boundary. */
class AttachmentPersistenceSecurityTest {
    @Test fun completePersistedTranscriptMustNotContainTransportCredential() = runTest {
        val sentinel = "SYNTHETIC_ATTACHMENT_CREDENTIAL_92f6c18a"
        val liveUrl = "https://fixture.invalid/api/files/download?path=%2Fartifacts%2Freport.png&token=$sentinel"
        val attachment = Attachment(
            uri = liveUrl,
            name = "report.png",
            mimeType = "image/png",
            gatewayUrl = liveUrl,
            source = AttachmentSource.GATEWAY,
        )
        val dao = FakeChatMessageDao()
        val repository = ChatPersistenceRepository(dao)
        val (owner, _) = repository.openTranscript("synthetic-target", "synthetic-session")
        val visible = ChatMessage(
            id = "visible", role = MessageRole.ASSISTANT, content = "Visible body",
            attachments = listOf(attachment),
        )
        val staged = ChatMessage(
            id = "staged", role = MessageRole.ASSISTANT, content = "Staged body",
            attachments = listOf(attachment),
        )
        assertTrue(repository.save(repository.prepare(owner, listOf(visible), ResponseLedger(), listOf(staged))))
        val state = requireNotNull(dao.transcriptState(owner.target, owner.session))
        val bodyRows = dao.getMessagesForSession(owner.session)
        assertTrue("Both indexed bodies must be durably materialized", bodyRows.size == 2)
        assertFalse("Complete durable payload contains a synthetic transport credential", state.payload.contains(sentinel))
        assertFalse("Durable body rows contain a synthetic transport credential", bodyRows.toString().contains(sentinel))
    }
}
