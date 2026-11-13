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
        val useSurface =
            !redirect && text.isNotBlank() && !text.trimStart().startsWith("/") &&
                delegate.supportsClientSurface(scope, NativeUiInstructions.SURFACE)
        return delegate.submitPrompt(
            scope,
            session,
            if (useSurface) text else RichAnswerPrompt.append(text),
            redirect,
            queued,
            if (useSurface) NativeUiInstructions.SURFACE else null,
        )
    }
}
