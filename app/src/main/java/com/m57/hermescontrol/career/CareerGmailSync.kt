package com.m57.hermescontrol.career

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.MessageDigest

internal data class CareerGmailIdPage(
    val ids: List<String>,
    val nextToken: String?,
)

internal class CareerGmailHttpException(
    val status: Int,
    message: String,
) : IllegalStateException(message)

@Serializable
internal data class CareerGmailCheckpoint(
    val pageToken: String? = null,
    val pendingIds: List<String> = emptyList(),
    val nextToken: String? = null,
    val complete: Boolean = false,
)

internal class CareerGmailSync(
    private val gmail: CareerGmailClient,
    private val local: CareerLocalStore,
    private val repository: CareerCompanyRepository,
) {
    suspend fun collect(
        authorization: CareerGmailAuthorizationResult.Authorized,
        companyId: String,
        restart: Boolean,
        onProgress: (Int, Boolean) -> Unit,
    ): Boolean =
        gate.withLock {
            withContext(Dispatchers.IO) {
                // Fetch rules from this company only, never the global connection list.
                val detail = repository.company(companyId)
                val senders = gmailApprovedSenders(detail.mail_senders.filter { it.company_id == companyId })
                require(senders.isNotEmpty()) { "この企業の送信元を「管理」で登録してください。" }
                val key = checkpointKey(companyId, authorization.accountEmail, senders)
                var checkpoint = if (restart) CareerGmailCheckpoint() else local.gmailCheckpoint(key)
                if (restart) local.saveGmailCheckpoint(key, checkpoint)
                if (checkpoint.complete) return@withContext false
                gmail.verifyAccount(authorization.accessToken, authorization.accountEmail)
                // Establish this company's history baseline even when the first mailbox scan is empty.
                repository.importClientMail(authorization.accountEmail, emptyList(), companyId)
                var examined = 0
                var saved = 0
                while (examined < 100 && !checkpoint.complete) {
                    if (checkpoint.pendingIds.isEmpty()) {
                        val page = gmail.listPage(authorization.accessToken, senders, checkpoint.pageToken)
                        check(page.nextToken == null || page.nextToken != checkpoint.pageToken) {
                            "Gmailの続きの位置を確認できません。「最新から更新」で再確認してください。"
                        }
                        checkpoint = checkpoint.copy(pendingIds = page.ids, nextToken = page.nextToken)
                        if (page.ids.isEmpty()) {
                            checkpoint =
                                CareerGmailCheckpoint(pageToken = page.nextToken, complete = page.nextToken == null)
                            local.saveGmailCheckpoint(key, checkpoint)
                            // Bound empty pages too; never spin indefinitely on a changing mailbox.
                            examined += 25
                            continue
                        }
                        local.saveGmailCheckpoint(key, checkpoint)
                    }
                    val ids = checkpoint.pendingIds.take(minOf(10, 100 - examined))
                    val cached =
                        repository
                            .cachedMail(
                                authorization.accountEmail,
                                companyId,
                                ids,
                            ).intersect(ids.toSet())
                    val messages =
                        ids.filterNot { it in cached }.mapNotNull {
                            try {
                                gmail.readMessage(authorization.accessToken, it, senders.toSet())
                            } catch (error: CareerGmailHttpException) {
                                // A message removed between listing and reading must not block the rest.
                                if (error.status == 404) null else throw error
                            }
                        }
                    if (messages.isNotEmpty()) {
                        repository.importClientMail(
                            authorization.accountEmail,
                            messages,
                            companyId,
                        )
                    }
                    // Advance only after the server acknowledged this chunk. An uncertain response
                    // retries the same IDs; cache reuse and idempotent import prevent duplication.
                    val remaining = checkpoint.pendingIds.drop(ids.size)
                    checkpoint =
                        if (remaining.isEmpty()) {
                            CareerGmailCheckpoint(
                                pageToken = checkpoint.nextToken,
                                complete =
                                    checkpoint.nextToken == null,
                            )
                        } else {
                            checkpoint.copy(pendingIds = remaining)
                        }
                    local.saveGmailCheckpoint(key, checkpoint)
                    examined += ids.size
                    saved += cached.size + messages.size
                    onProgress(saved, !checkpoint.complete)
                }
                !checkpoint.complete
            }
        }

    companion object {
        // All company views share one queue; rapidly opening several cannot multiply Gmail traffic.
        private val gate = Mutex()

        internal fun checkpointKey(
            company: String,
            account: String,
            senders: List<String>,
        ): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    (
                        listOf(
                            company,
                            account.trim().lowercase(),
                        ) + senders.sorted()
                    ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
                ).joinToString("") { "%02x".format(it) }
    }
}
