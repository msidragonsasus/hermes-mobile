package com.m57.hermescontrol.career

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.m57.hermescontrol.BuildConfig

internal class CareerGmailAuthManager(
    context: Context,
    private val local: CareerLocalStore,
) {
    private val appContext = context.applicationContext

    val isConfigured: Boolean
        get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    suspend fun connectedEmail(): String? = local.gmailAccountEmail()

    suspend fun beginAuthorization(activity: Activity): CareerGmailAuthorizationResult {
        if (!isConfigured) {
            return CareerGmailAuthorizationResult.Failure("Google連携の設定がありません。")
        }
        val accountEmail =
            runCatching { ensureSignedIn(activity) }.getOrElse { error ->
                return CareerGmailAuthorizationResult.Failure(
                    error.message ?: "Googleアカウントを選択できませんでした。",
                )
            }
        return authorize(activity, accountEmail)
    }

    suspend fun completeAuthorization(
        activity: Activity,
        data: Intent?,
    ): CareerGmailAuthorizationResult =
        runCatching {
            val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            val accountEmail =
                local.gmailAccountEmail()
                    ?: throw IllegalStateException("Googleアカウント情報を確認できませんでした。")
            CareerGmailAuthorizationResult.Authorized(
                accessToken =
                    result.accessToken?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Gmailの読み取り許可を確認できませんでした。"),
                accountEmail = accountEmail,
            )
        }.getOrElse { error ->
            CareerGmailAuthorizationResult.Failure(error.message ?: "Gmailの読み取りを許可できませんでした。")
        }

    suspend fun disconnect(activity: Activity): Result<Unit> =
        runCatching {
            local.gmailAccountEmail()?.let { email ->
                Identity
                    .getAuthorizationClient(activity)
                    .revokeAccess(
                        RevokeAccessRequest
                            .builder()
                            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                            .setScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
                            .build(),
                    ).awaitCareerResult()
            }
            CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
            local.setGmailAccountEmail(null)
        }

    private suspend fun ensureSignedIn(activity: Activity): String {
        local.gmailAccountEmail()?.let { return it }
        val credential =
            CredentialManager
                .create(activity)
                .getCredential(
                    context = activity,
                    request =
                        GetCredentialRequest(
                            listOf(
                                GetGoogleIdOption
                                    .Builder()
                                    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                                    .setFilterByAuthorizedAccounts(false)
                                    .setAutoSelectEnabled(false)
                                    .build(),
                            ),
                        ),
                ).credential
        val googleCredential =
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleIdTokenCredential.createFrom(credential.data)
            } else {
                throw IllegalStateException("Googleアカウント情報を取得できませんでした。")
            }
        val email =
            googleCredential.id.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Googleアカウントのメールアドレスを取得できませんでした。")
        local.setGmailAccountEmail(email)
        return email
    }

    private suspend fun authorize(
        activity: Activity,
        accountEmail: String,
    ): CareerGmailAuthorizationResult =
        runCatching {
            val result =
                Identity
                    .getAuthorizationClient(activity)
                    .authorize(
                        AuthorizationRequest
                            .builder()
                            .setAccount(Account(accountEmail, GOOGLE_ACCOUNT_TYPE))
                            .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
                            .build(),
                    ).awaitCareerResult()
            if (result.hasResolution()) {
                CareerGmailAuthorizationResult.NeedsResolution(requireNotNull(result.pendingIntent))
            } else {
                CareerGmailAuthorizationResult.Authorized(
                    accessToken =
                        result.accessToken?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("Gmailの読み取り許可を確認できませんでした。"),
                    accountEmail = accountEmail,
                )
            }
        }.getOrElse { error ->
            CareerGmailAuthorizationResult.Failure(error.message ?: "Gmailの読み取りを許可できませんでした。")
        }

    private companion object {
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

internal sealed interface CareerGmailAuthorizationResult {
    data class Authorized(
        val accessToken: String,
        val accountEmail: String,
    ) : CareerGmailAuthorizationResult

    data class NeedsResolution(
        val pendingIntent: PendingIntent,
    ) : CareerGmailAuthorizationResult

    data class Failure(
        val message: String,
    ) : CareerGmailAuthorizationResult
}
