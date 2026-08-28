package me.avinas.tempo.data.drive

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Google identity and the Drive scopes used by Tempo.
 *
 * Two least-privilege Drive scopes are requested together:
 * - drive.file: existing user-visible .tempo backups created by Tempo
 * - drive.appdata: hidden cross-device listening-history transport
 *
 * Keeping them in one authorization request is important because Google access
 * tokens are refreshed as a unit by Play services. A background refresh must not
 * silently drop one feature's permission while keeping the other.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStorage: GoogleDriveTokenStorage,
    private val historySyncSettings: DriveHistorySyncSettingsManager
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        // Keep these names aligned with DriveHistorySyncWorker. They live here as
        // strings deliberately so the authentication/data layer does not depend
        // on the worker package just to clean up scheduled work at sign-out.
        private const val HISTORY_SYNC_WORK_NAME = "drive_history_sync"
        private const val HISTORY_SYNC_MANUAL_WORK_NAME = "drive_history_sync_manual"

        private val REQUIRED_DRIVE_SCOPES = listOf(
            Scope(DriveScopes.DRIVE_FILE),
            Scope(DriveScopes.DRIVE_APPDATA)
        )
        private val REQUIRED_DRIVE_SCOPE_URIS = setOf(
            DriveScopes.DRIVE_FILE,
            DriveScopes.DRIVE_APPDATA
        )
    }

    private val authorizationClient = Identity.getAuthorizationClient(context)

    private val _currentAccount = MutableStateFlow<GoogleAccount?>(null)
    val currentAccount: StateFlow<GoogleAccount?> = _currentAccount.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _needsDriveConsent = MutableStateFlow(false)
    val needsDriveConsent: StateFlow<Boolean> = _needsDriveConsent.asStateFlow()

    private var authorizationResult: AuthorizationResult? = null

    private fun configuredWebClientId(): String? =
        BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().takeIf { it.isNotEmpty() }

    suspend fun signIn(activity: Activity): GoogleSignInResult = withContext(Dispatchers.Main) {
        val webClientId = configuredWebClientId()
            ?: return@withContext GoogleSignInResult.Error(
                "Google Sign-In is not configured in this build (missing GOOGLE_WEB_CLIENT_ID)."
            ).also {
                Log.e(TAG, "Cannot start Google Sign-In: GOOGLE_WEB_CLIENT_ID is blank")
            }

        try {
            val credentialManager = CredentialManager.create(activity)
            val googleSignInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleSignInOption)
                .build()
            handleSignInResponse(credentialManager.getCredential(activity, request))
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credential available for explicit sign-in", e)
            GoogleSignInResult.Error(
                "Google Sign-In is unavailable. Check Google Play services and your Google account settings, then try again.",
                e
            )
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Google sign-in failed", e)
            GoogleSignInResult.Error("Sign-in failed: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected Google sign-in error", e)
            GoogleSignInResult.Error("Unexpected error: ${e.message}", e)
        }
    }

    private suspend fun handleSignInResponse(response: GetCredentialResponse): GoogleSignInResult {
        val credential = response.credential
        if (credential !is CustomCredential) {
            return GoogleSignInResult.Error("Unexpected credential class: ${credential::class.java.name}")
        }

        val isGoogleIdCredential =
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
        if (!isGoogleIdCredential) {
            return GoogleSignInResult.Error("Unexpected credential type: ${credential.type}")
        }

        return try {
            val googleIdCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val email = googleIdCredential.email?.takeIf { it.isNotBlank() }
                ?: return GoogleSignInResult.Error(
                    "Google Sign-In did not return an email address for the selected account."
                )
            val account = GoogleAccount(
                email = email,
                displayName = googleIdCredential.displayName,
                photoUrl = googleIdCredential.profilePictureUri?.toString()
            )
            _currentAccount.value = account
            _isSignedIn.value = true
            tokenStorage.saveAccountInfo(account.email, account.displayName, account.photoUrl)

            // Identity and Drive authorization are intentionally separate. Sign-in
            // succeeds even if the subsequent Drive consent still needs UI.
            requestDriveAuthorization()
            GoogleSignInResult.Success(account)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Google ID credential", e)
            GoogleSignInResult.Error("Failed to parse credential: ${e.message}", e)
        }
    }

    private suspend fun requestDriveAuthorization(): Boolean = withContext(Dispatchers.IO) {
        try {
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            if (authorizationResult?.hasResolution() == true) {
                _needsDriveConsent.value = true
                return@withContext false
            }
            persistAuthorizedToken(authorizationResult)
        } catch (e: ApiException) {
            if (e.status.resolution != null) {
                _needsDriveConsent.value = true
                false
            } else {
                Log.e(TAG, "Drive authorization failed (${e.status.statusCode})", e)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Drive authorization failed", e)
            false
        }
    }

    suspend fun completeConsentFlow(): Boolean = withContext(Dispatchers.IO) {
        try {
            _needsDriveConsent.value = false
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            if (authorizationResult?.hasResolution() == true) {
                _needsDriveConsent.value = true
                return@withContext false
            }
            persistAuthorizedToken(authorizationResult)
        } catch (e: ApiException) {
            if (e.status.resolution != null) _needsDriveConsent.value = true
            Log.e(TAG, "Failed to complete Drive consent (${e.status.statusCode})", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete Drive consent", e)
            false
        }
    }

    private fun persistAuthorizedToken(result: AuthorizationResult?): Boolean {
        val accessToken = result?.accessToken
        val grantedScopes = result?.grantedScopes.orEmpty()
        val hasScopes = hasAllRequiredDriveScopes(grantedScopes)
        val valid = accessToken != null && hasScopes

        if (valid) {
            tokenStorage.saveAccessToken(accessToken!!)
            _needsDriveConsent.value = false
            Log.i(TAG, "Drive authorization granted for backup + appData sync")
        } else {
            tokenStorage.clearToken()
            if (!hasScopes) {
                Log.w(
                    TAG,
                    "Drive authorization is missing one or more required least-privilege scopes. Granted: $grantedScopes"
                )
            }
        }
        return valid
    }

    private fun hasAllRequiredDriveScopes(grantedScopes: Collection<String>): Boolean =
        REQUIRED_DRIVE_SCOPE_URIS.all { required ->
            grantedScopes.any { granted -> granted == required || granted.contains(required) }
        }

    fun getAuthorizationResult(): AuthorizationResult? = authorizationResult

    private fun buildDriveAuthRequest(): AuthorizationRequest {
        val webClientId = requireNotNull(configuredWebClientId()) {
            "GOOGLE_WEB_CLIENT_ID is required for Google Drive authorization"
        }
        val builder = AuthorizationRequest.Builder()
            .setRequestedScopes(REQUIRED_DRIVE_SCOPES)

        _currentAccount.value?.let { account ->
            builder.setAccount(Account(account.email, "com.google"))
        }
        builder.requestOfflineAccess(webClientId)
        return builder.build()
    }

    fun getDriveAuthorizationPendingIntent() = authorizationResult?.pendingIntent

    fun updateAuthorizationResult(result: AuthorizationResult) {
        authorizationResult = result
        if (!result.hasResolution()) {
            persistAuthorizedToken(result)
        }
    }

    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            authorizationResult?.takeIf { !it.hasResolution() }?.accessToken?.let {
                return@withContext it
            }

            val persisted = tokenStorage.getAccessToken()
            if (persisted != null) {
                if (tokenStorage.isTokenExpired()) {
                    if (refreshAccessToken()) {
                        return@withContext tokenStorage.getAccessToken()
                    }
                    return@withContext null
                }
                return@withContext persisted
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain Drive access token", e)
            null
        }
    }

    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            if (authorizationResult?.hasResolution() == true) {
                _needsDriveConsent.value = true
                return@withContext false
            }
            persistAuthorizedToken(authorizationResult)
        } catch (e: ApiException) {
            if (e.status.resolution != null) _needsDriveConsent.value = true
            Log.w(TAG, "Drive token refresh failed (${e.status.statusCode})", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Drive token refresh failed", e)
            false
        }
    }

    fun invalidateAuthorization() {
        authorizationResult = null
    }

    fun clearPersistedAccessToken() {
        tokenStorage.clearToken()
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Credential Manager cleanup failed during sign-out", e)
        } finally {
            // Google sign-out is also a privacy boundary for the optional history
            // transport. Clear its opt-in/status and remove periodic/manual work
            // immediately so no background task keeps trying to use a signed-out
            // account. Local listening history and the stable device id remain.
            try {
                historySyncSettings.clearForSignOut()
                val workManager = WorkManager.getInstance(context)
                workManager.cancelUniqueWork(HISTORY_SYNC_WORK_NAME)
                workManager.cancelUniqueWork(HISTORY_SYNC_MANUAL_WORK_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear Drive history sync state during sign-out", e)
            }

            tokenStorage.clearAll()
            authorizationResult = null
            _currentAccount.value = null
            _isSignedIn.value = false
            _needsDriveConsent.value = false
        }
    }

    suspend fun restoreSession(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        val webClientId = configuredWebClientId() ?: return@withContext false
        try {
            val credentialManager = CredentialManager.create(activity)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            handleSignInResponse(credentialManager.getCredential(activity, request)) is GoogleSignInResult.Success
        } catch (e: NoCredentialException) {
            false
        } catch (e: GetCredentialCancellationException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore Google session", e)
            false
        }
    }

    /**
     * Background-safe restoration used by WorkManager. No UI is shown; if Google
     * needs fresh consent for drive.appdata after upgrading from an older Tempo
     * version, this returns false and the settings screen can request it later.
     */
    suspend fun restoreSessionSilently(): Boolean = withContext(Dispatchers.IO) {
        if (_isSignedIn.value && authorizationResult?.accessToken != null) return@withContext true
        if (configuredWebClientId() == null || !tokenStorage.hasAccountInfo()) return@withContext false

        val storedAccount = tokenStorage.getStoredAccount() ?: return@withContext false
        _currentAccount.value = storedAccount

        try {
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            if (authorizationResult?.hasResolution() == true) {
                _needsDriveConsent.value = true
                return@withContext false
            }
            if (persistAuthorizedToken(authorizationResult)) {
                _isSignedIn.value = true
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent Drive authorization failed", e)
        }

        // A non-expired cached token can keep existing features alive, but after
        // this release tokens are normally refreshed with both required scopes.
        if (tokenStorage.hasToken() && !tokenStorage.isTokenExpired()) {
            _isSignedIn.value = true
            return@withContext true
        }
        false
    }
}
