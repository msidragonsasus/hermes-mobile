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
    ): SubmissionResult<PromptAcceptance> =
        delegate.submitPrompt(
            scope,
            session,
            RichAnswerPrompt.append(text),
            redirect,
            queued,
        )
}
