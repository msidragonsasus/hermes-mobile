package com.m57.hermescontrol.miniapp.workbench

import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal interface WorkbenchRepository {
    fun isCurrent(): Boolean

    suspend fun snapshot(): WorkbenchSnapshot

    suspend fun editor(): EditorSnapshot? = null

    suspend fun mutate(
        payload: JsonObject,
        pending: WorkbenchPending,
    ): WorkbenchReceipt

    suspend fun recover(pending: WorkbenchPending): WorkbenchReceipt
}

internal class WorkbenchUnavailable(
    val disabled: Boolean = false,
) : Exception()

internal class DashboardWorkbenchRepository(
    private val scope: String,
    private val company: String,
    private val api: HermesApiService = ApiClient.hermesApi,
    private val current: () -> Boolean = {
        scope == careerScope() && AuthManager.isGatedMode() &&
            ApiClient.hermesApi === api
    },
) : WorkbenchRepository {
    private val profile =
        scope
            .split('\n')
            .getOrNull(2)
            .orEmpty()
            .ifBlank { "default" }

    override fun isCurrent(): Boolean = current()

    private suspend fun <T> call(
        request: suspend () -> Response<ResponseBody>,
        optionalNotFound: (() -> T)? = null,
        parse: (String) -> T,
    ): T {
        if (!isCurrent()) throw WorkbenchUnavailable()
        try {
            val response = request()
            try {
                return withContext(Dispatchers.IO) {
                    response.errorBody()?.close()
                    response.body().use { body ->
                        if (isCurrent() && response.code() == 404 &&
                            optionalNotFound != null
                        ) {
                            return@withContext optionalNotFound()
                        }
                        if (!isCurrent() || !response.isSuccessful ||
                            body == null
                        ) {
                            throw WorkbenchUnavailable(response.code() in setOf(403, 404))
                        }
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (bytes.size() + count > 240000) throw WorkbenchUnavailable()
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val text =
                            Charsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(
                                    CodingErrorAction.REPORT,
                                ).onUnmappableCharacter(
                                    CodingErrorAction.REPORT,
                                ).decode(ByteBuffer.wrap(bytes.toByteArray()))
                                .toString()
                        val result = parse(text)
                        if (!isCurrent()) throw WorkbenchUnavailable()
                        result
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { response.body()?.close() }
                    runCatching { response.errorBody()?.close() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: WorkbenchUnavailable) {
            throw unavailable
        } catch (_: Exception) {
            throw WorkbenchUnavailable()
        }
    }

    override suspend fun editor(): EditorSnapshot? =
        call(
            { api.careerWorkbenchEditor(company, profile) },
            { null },
        ) { WorkbenchEditorParser.parse(it, company, profile) }

    override suspend fun snapshot(): WorkbenchSnapshot =
        call({
            api.careerWorkbench(company, profile)
        }) { WorkbenchParser.snapshot(it, company, profile) }

    override suspend fun mutate(
        payload: JsonObject,
        pending: WorkbenchPending,
    ): WorkbenchReceipt =
        call({
            api.careerWorkbenchWrite(company, profile, payload)
        }) { WorkbenchParser.receipt(it, company, profile, pending) }

    override suspend fun recover(pending: WorkbenchPending): WorkbenchReceipt =
        call({
            api.careerWorkbenchReceipt(company, pending.operationId, profile)
        }) { WorkbenchParser.receipt(it, company, profile, pending) }
}
