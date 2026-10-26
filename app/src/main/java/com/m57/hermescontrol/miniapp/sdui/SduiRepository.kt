package com.m57.hermescontrol.miniapp.sdui

import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

/** Outcome of a read. Failures are categories; response bodies and messages are never kept. */
internal sealed interface SduiFetch<out T> {
    data class Ok<T>(
        val value: T,
    ) : SduiFetch<T>

    /** The payload was refused as a whole. */
    data class Rejected(
        val reason: SduiRejection,
    ) : SduiFetch<Nothing>

    /** 404: the server (or company) does not offer this. */
    data object NotFound : SduiFetch<Nothing>

    /** 401 / 403. */
    data object Unauthorized : SduiFetch<Nothing>

    /** Network failure or any other HTTP status. */
    data object Unavailable : SduiFetch<Nothing>

    /** Connection, profile or client changed while the call was outstanding. The result was dropped. */
    data object ConnectionChanged : SduiFetch<Nothing>
}

/** Outcome of the one write. [Ambiguous] means the server may or may not have accepted it. */
internal sealed interface SduiCheckResult {
    data class Accepted(
        val receipt: SduiReceipt,
    ) : SduiCheckResult

    /** The connection changed before anything was sent. Definitely not delivered. */
    data object NotSent : SduiCheckResult

    /** The connection changed while the request was outstanding. The response was dropped. */
    data object ConnectionChanged : SduiCheckResult

    /** 409 `mobile_release_changed`: the server is on a different release than the one the request named. */
    data object ReleaseMismatch : SduiCheckResult

    /** 409 `operation_conflict`: the operation id is already bound to another company or release. */
    data object OperationConflict : SduiCheckResult

    data object Unauthorized : SduiCheckResult

    data object NotFound : SduiCheckResult

    /** A known refusal code, or any other definitive 4xx. The server answered and did not accept the request. */
    data object Refused : SduiCheckResult

    /** Timeout, I/O failure, 5xx, 408, an unknown or unreadable 409, or a 2xx whose receipt cannot be trusted. */
    data object Ambiguous : SduiCheckResult
}

internal interface SduiRepository {
    /** True only while the connection, profile and authenticated client are the ones this host was built for. */
    fun isCurrent(): Boolean

    suspend fun manifest(): SduiFetch<SduiManifest>

    suspend fun screen(
        manifest: SduiManifest,
        companyId: String,
    ): SduiFetch<SduiScreen>

    /**
     * Sends the check. [releaseId] is the release the request NAMES; a retry passes the one from the
     * first dispatch, which may differ from the release of the [screen] now displayed.
     */
    suspend fun check(
        screen: SduiScreen,
        operationId: String,
        releaseId: String = screen.releaseId,
    ): SduiCheckResult
}

/**
 * Reuses the app's authenticated [ApiClient.hermesApi]; there is no second client. The scope,
 * profile and client are captured once and re-verified before and after every await.
 */
internal class DashboardSduiRepository(
    private val scope: String,
    private val api: HermesApiService = ApiClient.hermesApi,
    private val current: () -> Boolean = { defaultIsCurrent(scope, api) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val companyHint: String? = null,
) : SduiRepository {
    /** The scope is `baseUrl \n selectedProfile \n activeProfile`; the last part is the server profile. */
    private val profile =
        scope
            .split('\n')
            .getOrNull(2)
            .orEmpty()
            .ifBlank { "default" }

    override fun isCurrent(): Boolean = current()

    override suspend fun manifest(): SduiFetch<SduiManifest> =
        fetch({ api.careerMobileManifest(profile, companyHint) }) { SduiParser.parseManifest(it) }

    override suspend fun screen(
        manifest: SduiManifest,
        companyId: String,
    ): SduiFetch<SduiScreen> =
        fetch({ api.careerMobileSduiCompany(companyId, profile) }) {
            SduiParser.parseScreen(it, manifest, companyId, profile)
        }

    override suspend fun check(
        screen: SduiScreen,
        operationId: String,
        releaseId: String,
    ): SduiCheckResult {
        if (!isCurrent()) return SduiCheckResult.NotSent
        val request =
            SduiMyPageCheckRequest(
                protocol_version = SduiContract.PROTOCOL_VERSION,
                release_id = releaseId,
                company_id = screen.companyId,
                operation_id = operationId,
            )
        val response =
            try {
                api.careerMobileMyPageCheck(profile, request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The request may have reached the server; if the scope moved on, say so instead.
                return if (isCurrent()) SduiCheckResult.Ambiguous else SduiCheckResult.ConnectionChanged
            }
        if (!isCurrent()) {
            response.discard()
            return SduiCheckResult.ConnectionChanged
        }
        if (!response.isSuccessful) {
            val code = response.code()
            // Only a bounded machine code is read from an error body, and only where it can change the outcome.
            val errorCode =
                if (code == 409 || code == 404) {
                    readBounded(response.errorBody(), SduiContract.MAX_ERROR_BODY_BYTES)
                        ?.let(SduiParser::parseErrorCode)
                } else {
                    null
                }
            response.discard()
            if (!isCurrent()) return SduiCheckResult.ConnectionChanged
            return classify(code, errorCode)
        }
        val bytes = readBounded(response.body(), SduiContract.MAX_BODY_BYTES)
        // Body handling suspends; a receipt that arrives after the scope moved on is never applied.
        if (!isCurrent()) return SduiCheckResult.ConnectionChanged
        if (bytes == null) return SduiCheckResult.Ambiguous
        val expected = SduiReceiptExpectation(releaseId, screen.companyId, profile, operationId)
        return when (val parsed = SduiParser.parseReceipt(bytes, expected)) {
            is SduiParsed.Valid -> SduiCheckResult.Accepted(parsed.value)
            is SduiParsed.Rejected -> SduiCheckResult.Ambiguous
        }
    }

    /**
     * Any 409 that is not an exactly known machine code stays ambiguous: it may be an answer about an
     * earlier attempt. Unknown 4xx are a first refusal; the host decides what a retry may conclude.
     */
    private fun classify(
        status: Int,
        error: SduiErrorCode?,
    ): SduiCheckResult =
        when {
            status == 409 -> {
                when (error) {
                    SduiErrorCode.MOBILE_RELEASE_CHANGED -> SduiCheckResult.ReleaseMismatch

                    SduiErrorCode.OPERATION_CONFLICT -> SduiCheckResult.OperationConflict

                    SduiErrorCode.MYPAGE_NOT_CONNECTED,
                    SduiErrorCode.COMPANY_CLOSED,
                    SduiErrorCode.COMPANY_MISSING,
                    -> SduiCheckResult.Refused

                    null -> SduiCheckResult.Ambiguous
                }
            }

            status == 401 || status == 403 -> {
                SduiCheckResult.Unauthorized
            }

            status == 404 -> {
                if (error == SduiErrorCode.COMPANY_MISSING) SduiCheckResult.Refused else SduiCheckResult.NotFound
            }

            status == 408 || status >= 500 -> {
                SduiCheckResult.Ambiguous
            }

            status in 400..499 -> {
                SduiCheckResult.Refused
            }

            else -> {
                SduiCheckResult.Ambiguous
            }
        }

    private suspend fun <T> fetch(
        call: suspend () -> Response<ResponseBody>,
        parse: (ByteArray) -> SduiParsed<T>,
    ): SduiFetch<T> {
        if (!isCurrent()) return SduiFetch.ConnectionChanged
        val response =
            try {
                call()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return if (isCurrent()) SduiFetch.Unavailable else SduiFetch.ConnectionChanged
            }
        if (!isCurrent()) {
            response.discard()
            return SduiFetch.ConnectionChanged
        }
        if (!response.isSuccessful) {
            val code = response.code()
            response.discard()
            return when (code) {
                404 -> SduiFetch.NotFound
                401, 403 -> SduiFetch.Unauthorized
                else -> SduiFetch.Unavailable
            }
        }
        val bytes = readBounded(response.body(), SduiContract.MAX_BODY_BYTES)
        if (!isCurrent()) return SduiFetch.ConnectionChanged
        if (bytes == null) return SduiFetch.Rejected(SduiRejection.TOO_LARGE)
        return when (val parsed = parse(bytes)) {
            is SduiParsed.Valid -> SduiFetch.Ok(parsed.value)
            is SduiParsed.Rejected -> SduiFetch.Rejected(parsed.reason)
        }
    }

    /**
     * Buffers at most [limit] bytes on the IO dispatcher; null means the body is over the limit or
     * unreadable. Exactly the buffered bytes are taken, so nothing past the limit is ever read, even
     * from a source that does not end.
     */
    private suspend fun readBounded(
        body: ResponseBody?,
        limit: Int,
    ): ByteArray? =
        withContext(io) {
            if (body == null) return@withContext ByteArray(0)
            try {
                body.use {
                    val source = it.source()
                    // `request` is true only if more than `limit` bytes are available: over the limit.
                    if (source.request(limit + 1L)) null else source.readByteArray(source.buffer.size)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun Response<ResponseBody>.discard() {
        // Not cancellable: a body we stopped caring about must still be closed.
        withContext(NonCancellable + io) {
            try {
                body()?.close()
                errorBody()?.close()
            } catch (_: Exception) {
                // Closing a body we are discarding must not change the outcome.
            }
        }
    }

    private companion object {
        fun defaultIsCurrent(
            scope: String,
            api: HermesApiService,
        ): Boolean =
            try {
                scope == careerScope() && AuthManager.isGatedMode() && ApiClient.hermesApi === api
            } catch (_: Exception) {
                false
            }
    }
}
