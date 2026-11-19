package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/** Profile model changes target the profile in the URL and are sent only once. */
internal suspend fun HermesApiService.saveProfileModel(
    profile: String,
    provider: String,
    model: String,
    scope: SpecialistRequestScope? = null,
): String? =
    settingsWriteError {
        updateProfileModel(profile, UpdateProfileModelRequest(provider, model), scope)
    }

/** Return safe UI copy without forwarding response bodies or exception text. */
internal suspend fun settingsWriteError(write: suspend () -> Response<Unit>): String? =
    try {
        val response = write()
        val code = response.code()
        response.errorBody()?.close()
        when {
            response.isSuccessful -> null
            code == 400 || code == 422 -> "設定がサーバーに受け付けられませんでした（HTTP $code）。設定を再読み込みしてください。"
            code == 401 -> "認証が切れました。ログインし直してください。"
            code == 403 -> "設定を変更する権限がありません（HTTP 403）。"
            code == 404 -> "対象の設定またはプロフィールが見つかりません（HTTP 404）。"
            code == 409 -> "サーバーの設定が更新されています（HTTP 409）。設定を再読み込みしてください。"
            else -> "設定の保存に失敗しました（HTTP $code）。"
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        "通信が中断され、保存結果を確認できません。設定を再読み込みしてください。"
    } catch (_: Exception) {
        "保存結果を読み取れません。設定を再読み込みしてください。"
    }
