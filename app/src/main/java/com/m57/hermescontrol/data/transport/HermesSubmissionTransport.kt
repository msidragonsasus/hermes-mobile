package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult

/** Typed write surface on the existing transport; no reconnect or automatic fallback. */
interface HermesSubmissionTransport {
    fun configuredScope(): ConnectionScope?

    fun isReady(scope: ConnectionScope): Boolean

    suspend fun submitPrompt(
        scope: ConnectionScope,
        session: SessionRef,
        text: String,
        redirect: Boolean = false,
        queued: Boolean = false,
    ): SubmissionResult<PromptAcceptance>

    suspend fun attach(
        scope: ConnectionScope,
        session: SessionRef,
        attachment: Attachment,
        dataUrl: String,
    ): SubmissionResult<String?>

    suspend fun resume(
        scope: ConnectionScope,
        savedSessionId: String,
    ): SubmissionResult<SessionRef>
}
