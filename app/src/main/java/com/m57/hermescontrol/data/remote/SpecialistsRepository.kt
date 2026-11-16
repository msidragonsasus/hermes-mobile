package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.BrainstormSnapshot
import com.m57.hermescontrol.data.model.CreateSpecialistRequest
import com.m57.hermescontrol.data.model.CreateSpecialistResponse
import com.m57.hermescontrol.data.model.SpecialistCapabilitiesResponse
import com.m57.hermescontrol.data.model.SpecialistSetupConfirmRequest
import com.m57.hermescontrol.data.model.SpecialistSetupDraftRequest
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.model.SpecialistTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import retrofit2.Response

sealed interface SpecialistApiOutcome<out T> {
    data class Success<T>(
        val data: T,
    ) : SpecialistApiOutcome<T>

    data class Unsupported(
        val code: String? = null,
    ) : SpecialistApiOutcome<Nothing>

    data class Conflict(
        val code: String?,
        val message: String,
    ) : SpecialistApiOutcome<Nothing>

    data class Validation(
        val code: String?,
        val message: String,
    ) : SpecialistApiOutcome<Nothing>

    data class UncertainTransport(
        val message: String,
    ) : SpecialistApiOutcome<Nothing>
}

sealed interface SpecialistCapabilitiesState {
    data object Loading : SpecialistCapabilitiesState

    data class Supported(
        val capabilities: SpecialistCapabilitiesResponse,
    ) : SpecialistCapabilitiesState

    data class LegacyUnsupported(
        val reason: String? = null,
    ) : SpecialistCapabilitiesState

    data class Unavailable(
        val message: String,
    ) : SpecialistCapabilitiesState
}

internal object SpecialistErrorParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun extractCodeAndMessage(rawBody: String?): Pair<String?, String?> {
        if (rawBody.isNullOrBlank()) return null to null
        val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull() ?: return null to null
        val detail = root["detail"]
        return when (detail) {
            is JsonObject -> {
                val code = (detail["code"] as? JsonPrimitive)?.contentOrNull
                val msg = (detail["message"] as? JsonPrimitive)?.contentOrNull
                code to (msg ?: code)
            }

            is JsonPrimitive -> {
                detail.contentOrNull to detail.contentOrNull
            }

            else -> {
                null to null
            }
        }
    }
}

class SpecialistsRepository(
    private val apiProvider: () -> HermesApiService = { ApiClient.hermesApi },
) {
    suspend fun fetchCapabilities(): SpecialistCapabilitiesState =
        when (val outcome = execute { it.getSpecialistCapabilities() }) {
            is SpecialistApiOutcome.Success -> SpecialistCapabilitiesState.Supported(outcome.data)
            is SpecialistApiOutcome.Unsupported -> SpecialistCapabilitiesState.LegacyUnsupported(outcome.code)
            is SpecialistApiOutcome.Conflict -> SpecialistCapabilitiesState.Unavailable(outcome.message)
            is SpecialistApiOutcome.Validation -> SpecialistCapabilitiesState.Unavailable(outcome.message)
            is SpecialistApiOutcome.UncertainTransport -> SpecialistCapabilitiesState.Unavailable(outcome.message)
        }

    suspend fun fetchTemplates(): SpecialistApiOutcome<List<SpecialistTemplate>> =
        when (val outcome = execute { it.getSpecialistTemplates() }) {
            is SpecialistApiOutcome.Success -> SpecialistApiOutcome.Success(outcome.data.templates)
            is SpecialistApiOutcome.Unsupported -> outcome
            is SpecialistApiOutcome.Conflict -> outcome
            is SpecialistApiOutcome.Validation -> outcome
            is SpecialistApiOutcome.UncertainTransport -> outcome
        }

    suspend fun createSpecialist(request: CreateSpecialistRequest): SpecialistApiOutcome<CreateSpecialistResponse> =
        execute { it.createSpecialist(request) }

    suspend fun getSetup(name: String): SpecialistApiOutcome<SpecialistSetupSnapshot> =
        execute { it.getSpecialistSetup(name) }

    suspend fun saveDraft(
        name: String,
        request: SpecialistSetupDraftRequest,
    ): SpecialistApiOutcome<SpecialistSetupSnapshot> = execute { it.generateSpecialistSetupDraft(name, request) }

    suspend fun confirmDraft(
        name: String,
        request: SpecialistSetupConfirmRequest,
    ): SpecialistApiOutcome<SpecialistSetupSnapshot> = execute { it.confirmSpecialistSetup(name, request) }

    suspend fun listBrainstorms(
        sessionId: String,
        profile: String = "default",
    ): SpecialistApiOutcome<List<BrainstormSnapshot>> =
        when (val outcome = execute { it.getBrainstorms(profile = profile, sessionId = sessionId) }) {
            is SpecialistApiOutcome.Success -> SpecialistApiOutcome.Success(outcome.data.items)
            is SpecialistApiOutcome.Unsupported -> outcome
            is SpecialistApiOutcome.Conflict -> outcome
            is SpecialistApiOutcome.Validation -> outcome
            is SpecialistApiOutcome.UncertainTransport -> outcome
        }

    suspend fun getBrainstorm(
        id: String,
        sessionId: String,
        profile: String = "default",
    ): SpecialistApiOutcome<BrainstormSnapshot> =
        execute { it.getBrainstorm(id = id, profile = profile, sessionId = sessionId) }

    private suspend fun <T> execute(call: suspend (HermesApiService) -> Response<T>): SpecialistApiOutcome<T> =
        try {
            val response = call(apiProvider())
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    SpecialistApiOutcome.Success(body)
                } else {
                    SpecialistApiOutcome.UncertainTransport("Empty response (${response.code()})")
                }
            } else {
                val rawError = runCatching { response.errorBody()?.string() }.getOrNull()
                val (code, detailMsg) = SpecialistErrorParser.extractCodeAndMessage(rawError)
                val fallbackMsg = detailMsg?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code()}"
                when (response.code()) {
                    404 -> SpecialistApiOutcome.Unsupported(code)
                    409 -> SpecialistApiOutcome.Conflict(code, fallbackMsg)
                    400, 422 -> SpecialistApiOutcome.Validation(code, fallbackMsg)
                    else -> SpecialistApiOutcome.UncertainTransport(fallbackMsg)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpecialistApiOutcome.UncertainTransport(e.message ?: "Connection failed")
        }
}
