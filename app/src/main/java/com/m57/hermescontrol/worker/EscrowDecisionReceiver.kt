package com.m57.hermescontrol.worker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EscrowDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != ACTION_ESCROW_DECISION) return
        val escrowId = intent.getStringExtra(EXTRA_ESCROW_ID)?.takeIf { it.isNotBlank() } ?: return
        val digest = intent.getStringExtra(EXTRA_DIGEST)?.takeIf { it.isNotBlank() } ?: return
        val denyOnly = intent.getBooleanExtra(EXTRA_DENY_ONLY, false)
        val decisionWire = intent.getStringExtra(EXTRA_DECISION) ?: return
        val inboxId = intent.getStringExtra(EXTRA_INBOX_ID)?.takeIf { it.isNotBlank() }
        val decision =
            EscrowDecision.entries.firstOrNull { it.wireValue == decisionWire } ?: return
        val appContext = context.applicationContext

        val pending = goAsync()
        receiverScope.launch {
            try {
                handleDecision(
                    context = appContext,
                    escrowId = escrowId,
                    digest = digest,
                    denyOnly = denyOnly,
                    decision = decision,
                    inboxId = inboxId,
                )
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleDecision(
        context: Context,
        escrowId: String,
        digest: String,
        denyOnly: Boolean,
        decision: EscrowDecision,
        inboxId: String?,
    ) {
        if (!WorkerAuthBootstrap.ensureReady(context) || !WorkerAuthBootstrap.isAuthenticated()) {
            WorkerNotifications.postDecisionOutcome(
                context = context,
                escrowId = escrowId,
                inboxId = null,
                message = context.getString(R.string.worker_approval_auth_required),
            )
            return
        }
        val request = WorkerDecisionRequests.build(decision, digest, denyOnly)
        if (request == null) {
            WorkerNotifications.postDecisionOutcome(
                context = context,
                escrowId = escrowId,
                inboxId = null,
                message = context.getString(R.string.worker_approval_unavailable),
            )
            return
        }

        val outcomeMessage =
            runCatching {
                val resp = ApiClient.hermesApi.decideWorkerEscrow(escrowId, request)
                val body = resp.body()
                when {
                    resp.isSuccessful && body?.ok == true -> {
                        if (!inboxId.isNullOrBlank()) {
                            runCatching { ApiClient.hermesApi.ackWorkerInbox(inboxId) }
                        }
                        if (decision == EscrowDecision.APPROVE) {
                            context.getString(R.string.worker_approval_approved)
                        } else {
                            context.getString(R.string.worker_approval_denied)
                        }
                    }

                    resp.code() == 409 || (resp.isSuccessful && body?.ok == false) -> {
                        context.getString(R.string.worker_approval_unavailable)
                    }

                    else -> {
                        context.getString(R.string.worker_approval_send_failed)
                    }
                }
            }.getOrElse {
                context.getString(R.string.worker_approval_send_failed)
            }

        val resolvedInboxId =
            if (outcomeMessage == context.getString(R.string.worker_approval_approved) ||
                outcomeMessage == context.getString(R.string.worker_approval_denied) ||
                outcomeMessage == context.getString(R.string.worker_approval_unavailable)
            ) {
                inboxId
            } else {
                null
            }
        WorkerNotifications.postDecisionOutcome(
            context = context,
            escrowId = escrowId,
            inboxId = resolvedInboxId,
            message = outcomeMessage,
        )
    }

    companion object {
        const val ACTION_ESCROW_DECISION = "com.m57.hermescontrol.worker.ACTION_ESCROW_DECISION"
        private const val EXTRA_ESCROW_ID = "escrow_id"
        private const val EXTRA_DIGEST = "digest"
        private const val EXTRA_DENY_ONLY = "deny_only"
        private const val EXTRA_DECISION = "decision"
        private const val EXTRA_INBOX_ID = "inbox_id"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun decisionPendingIntent(
            context: Context,
            escrowId: String,
            digest: String,
            denyOnly: Boolean,
            decision: EscrowDecision,
            inboxId: String,
        ): PendingIntent {
            val intent =
                Intent(context, EscrowDecisionReceiver::class.java).apply {
                    action = ACTION_ESCROW_DECISION
                    data = "hermes-escrow://$escrowId/${decision.wireValue}".toUri()
                    putExtra(EXTRA_ESCROW_ID, escrowId)
                    putExtra(EXTRA_DIGEST, digest)
                    putExtra(EXTRA_DENY_ONLY, denyOnly)
                    putExtra(EXTRA_DECISION, decision.wireValue)
                    putExtra(EXTRA_INBOX_ID, inboxId)
                }
            return PendingIntent.getBroadcast(
                context,
                ("$escrowId:${decision.wireValue}").hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
