package com.m57.hermescontrol.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException

internal sealed interface HomeFailure {
    data object NotGated : HomeFailure

    data object AuthRequired : HomeFailure

    data object Offline : HomeFailure

    data class ServerOutdated(
        val code: String?,
    ) : HomeFailure

    data class ServerUnavailable(
        val code: String?,
    ) : HomeFailure

    data object ProfileUnavailable : HomeFailure

    data object RunInProgress : HomeFailure

    data object SetupRequired : HomeFailure

    data class InvalidInput(
        val code: String,
    ) : HomeFailure

    data object Generic : HomeFailure
}

internal fun HttpException.errorCode(): String? =
    runCatching {
        // Retrofit has already buffered the error body. Read and close it once.
        val body = response()?.errorBody()?.use { it.string() } ?: return null
        val payload = Json.parseToJsonElement(body) as? JsonObject
        val detail = payload?.get("detail") as? JsonObject
        ((detail?.get("code") ?: payload?.get("code")) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    }.getOrNull()

internal fun homeFailureOf(t: Throwable): HomeFailure =
    when (t) {
        is IOException -> HomeFailure.Offline
        is HttpException -> homeHttpFailure(t.code(), t.errorCode())
        else -> HomeFailure.Generic
    }

internal fun homeHttpFailure(
    status: Int,
    code: String?,
): HomeFailure =
    when (status) {
        401 -> {
            HomeFailure.AuthRequired
        }

        403 -> {
            when (code) {
                "profile_mismatch" -> HomeFailure.ProfileUnavailable
                "cross_origin_write" -> HomeFailure.Generic
                else -> HomeFailure.AuthRequired
            }
        }

        404 -> {
            if (code == "profile_missing") HomeFailure.ProfileUnavailable else HomeFailure.ServerOutdated(code)
        }

        405, 501 -> {
            HomeFailure.ServerOutdated(code)
        }

        409 -> {
            when (code) {
                "refresh_running" -> HomeFailure.RunInProgress
                "setup_required" -> HomeFailure.SetupRequired
                else -> HomeFailure.Generic
            }
        }

        422 -> {
            when (code) {
                "invalid_settings", "source_required" -> HomeFailure.InvalidInput(code)
                else -> HomeFailure.Generic
            }
        }

        502, 503, 504 -> {
            if (status == 503 && code == "standard_auth_unavailable") {
                HomeFailure.ServerOutdated(code)
            } else {
                HomeFailure.ServerUnavailable(code)
            }
        }

        else -> {
            HomeFailure.Generic
        }
    }
