package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult

/** Presentation capability adapter; the underlying transport retains its scope/ACK/retry rules. */
internal class RichAnswerSubmissionTransport(
    private val delegate: HermesSubmissionTransport,
) : HermesSubmissionTransport by delegate {
    override suspend fun submitPrompt(
        scope: ConnectionScope,
        session: SessionRef,
        text: String,
        redirect: Boolean,
        queued: Boolean,
        surface: String?,
    ): SubmissionResult<PromptAcceptance> {
        val requestedSurface = surface ?: NativeUiInstructions.SURFACE
        // Image-only prompts have empty text. Their bytes are staged separately on the session.
        val supported =
            if (text.trimStart().startsWith("/")) null else delegate.clientSurfaceSupport(scope, requestedSurface)
        // session.redirect has no per-turn surface field. Keep its steer semantics and send clean
        // text on modern servers. Only confirmed legacy servers need the inline display descriptor.
        return delegate.submitPrompt(
            scope,
            session,
            if (supported == false) RichAnswerPrompt.append(text) else text,
            redirect,
            queued,
            requestedSurface.takeIf { supported == true && !redirect },
        )
    }
}
