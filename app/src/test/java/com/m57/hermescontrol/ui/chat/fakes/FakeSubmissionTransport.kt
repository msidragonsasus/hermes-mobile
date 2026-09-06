package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult

/** Explicit fake ACKs; legacy mocked calls preserve existing payload assertions. Never production DI. */
class FakeSubmissionTransport : HermesSubmissionTransport {
    var scope = ConnectionScope("synthetic-server", null, null, "synthetic-process", 1)
    var ready = true
    var nextPromptResult: SubmissionResult<PromptAcceptance>? = null
    var nextAttachmentResult: SubmissionResult<String?>? = null
    var beforePrompt: (suspend () -> Unit)? = null

    override fun configuredScope() = scope

    override fun isReady(scope: ConnectionScope) = ready && this.scope == scope

    override suspend fun submitPrompt(
        scope: ConnectionScope,
        session: SessionRef,
        text: String,
        redirect: Boolean,
        queued: Boolean,
    ): SubmissionResult<PromptAcceptance> {
        beforePrompt?.invoke()
        if (!isReady(scope) || nextPromptResult == SubmissionResult.NotSent) return SubmissionResult.NotSent
        val id =
            if (redirect) {
                HermesWsClient.sendRedirect(session.runtimeId, text, onSent = {})
            } else {
                HermesWsClient.sendMessage(session.runtimeId, text, onSent = {}, queued = queued)
            }
        return nextPromptResult
            ?: SubmissionResult.Confirmed(id, if (redirect) PromptAcceptance.REDIRECTED else PromptAcceptance.STREAMING)
    }

    override suspend fun attach(
        scope: ConnectionScope,
        session: SessionRef,
        attachment: Attachment,
        dataUrl: String,
    ): SubmissionResult<String?> {
        if (!isReady(scope) || nextAttachmentResult == SubmissionResult.NotSent) return SubmissionResult.NotSent
        val method = if (attachment.isImage) WsMethods.IMAGE_ATTACH_BYTES else WsMethods.FILE_ATTACH
        val params =
            if (attachment.isImage) {
                mapOf(
                    "session_id" to session.runtimeId,
                    "content_base64" to dataUrl,
                    "filename" to attachment.name,
                    "ext" to attachment.fileExtension,
                )
            } else {
                mapOf("session_id" to session.runtimeId, "data_url" to dataUrl, "name" to attachment.name)
            }
        val result = HermesWsClient.request(method, params).await() as? Map<*, *>
        return nextAttachmentResult ?: if (result?.get("attached") == true) {
            SubmissionResult.Confirmed("synthetic-attachment", result["ref_text"] as? String)
        } else {
            SubmissionResult.Unknown
        }
    }

    override suspend fun resume(
        scope: ConnectionScope,
        savedSessionId: String,
    ): SubmissionResult<SessionRef> = SubmissionResult.Unknown
}
