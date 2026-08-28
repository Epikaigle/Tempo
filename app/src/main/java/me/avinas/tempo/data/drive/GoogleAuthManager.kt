package me.avinas.tempo.data.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
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
 * Manages Google authentication using the modern Credential Manager API.
 * 
 * IMPORTANT: Credential Manager requires an Activity context for UI operations.
 * The signIn() and restoreSession() methods require an Activity to be passed.
 * 
 * This replaces the deprecated GoogleSignInClient approach and provides:
 * - Google Sign-In via Credential Manager bottom sheet
 * - Authorization for Google Drive API access
 * - Token management for API calls
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStorage: GoogleDriveTokenStorage
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        
        // Using drive.file scope - only accesses files created by this app
        // This avoids CASA security assessment requirements
        private val DRIVE_SCOPE = Scope(DriveScopes.DRIVE_FILE)
    }
    
    // CredentialManager is created per-call with Activity context
    private val authorizationClient = Identity.getAuthorizationClient(context)
    
    private val _currentAccount = MutableStateFlow<GoogleAccount?>(null)
    val currentAccount: StateFlow<GoogleAccount?> = _currentAccount.asStateFlow()
    
    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()
    
    // Indicates that Drive authorization requires user consent via pendingIntent
    private val _needsDriveConsent = MutableStateFlow(false)
    val needsDriveConsent: StateFlow<Boolean> = _needsDriveConsent.asStateFlow()
    
    // Cached authorization result for Drive API access.
    private var authorizationResult: AuthorizationResult? = null

    // ApiException can carry a consent resolution even when no AuthorizationResult
    // is returned. Keep that PendingIntent separately so the UI can always launch
    // the required Drive consent flow.
    private var authorizationResolution: PendingIntent? = null

    private fun configuredWebClientId(): String? =
        BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().takeIf { it.isNotEmpty() }
    
    /**
     * Sign in with Google using Credential Manager.
     * Shows the explicit Sign in with Google account chooser.
     * 
     * @param activity The Activity context REQUIRED for Credential Manager UI
     */
    suspend fun signIn(activity: Activity): GoogleSignInResult = withContext(Dispatchers.Main) {
        val webClientId = configuredWebClientId()
            ?: return@withContext GoogleSignInResult.Error(
                "Google Sign-In is not configured in this build (missing GOOGLE_WEB_CLIENT_ID)."
            ).also {
                Log.e(TAG, "Cannot start Google Sign-In: GOOGLE_WEB_CLIENT_ID is blank")
            }

        try {
            Log.d(TAG, "Starting explicit Google Sign-In with Credential Manager")
            
            // Create CredentialManager with Activity context
            val credentialManager = CredentialManager.create(activity)
            
            // A user tapping a dedicated "Sign in with Google" button should use
            // GetSignInWithGoogleOption. GetGoogleIdOption is intended for the
            // general credential selector / session-restore path and can return
            // NoCredentialException even when a Google account exists on-device.
            val googleSignInOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()
            
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleSignInOption)
                .build()
            
            // CRITICAL: Must use Activity context here, not Application context
            val response = credentialManager.getCredential(activity, request)
            
            handleSignInResponse(response)
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Sign-in cancelled by user")
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            // NoCredentialException does not mean there is no Google account on
            // the device. It means Credential Manager could not return a usable
            // credential for this request, so surface an actionable error instead
            // of the misleading "No Google accounts found" message.
            Log.w(TAG, "No Google credential available for explicit sign-in", e)
            GoogleSignInResult.Error(
                "Google Sign-In is unavailable. Check Google Play services and your Google account settings, then try again.",
                e
            )
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Sign-in failed", e)
            GoogleSignInResult.Error("Sign-in failed: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sign-in", e)
            GoogleSignInResult.Error("Unexpected error: ${e.message}", e)
        }
    }
    
    /**
     * Handle the credential response and extract Google account info.
     */
    private suspend fun handleSignInResponse(response: GetCredentialResponse): GoogleSignInResult {
        val credential = response.credential
        
        return when (credential) {
            is CustomCredential -> {
                val isGoogleIdCredential =
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL

                if (isGoogleIdCredential) {
                    try {
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
                        
                        // Persist account info for background session restoration
                        tokenStorage.saveAccountInfo(
                            email = account.email,
                            displayName = account.displayName,
                            photoUrl = account.photoUrl
                        )
                        
                        Log.i(TAG, "Successfully signed in as ${account.email}")

                        // An explicit account selection starts a fresh Drive authorization
                        // lifecycle. Never allow a token from a previous account/session to
                        // survive if the new authorization later needs consent or fails.
                        authorizationResult = null
                        authorizationResolution = null
                        _needsDriveConsent.value = false
                        tokenStorage.clearToken()
                        
                        // Authorize for Drive access
                        requestDriveAuthorization()
                        
                        GoogleSignInResult.Success(account)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Google ID credential", e)
                        GoogleSignInResult.Error("Failed to parse credential: ${e.message}", e)
                    }
                } else {
                    GoogleSignInResult.Error("Unexpected credential type: ${credential.type}")
                }
            }
            else -> {
                GoogleSignInResult.Error("Unexpected credential class: ${credential::class.java.name}")
            }
        }
    }
    
    /**
     * Request authorization for Google Drive API access.
     * This is called after successful sign-in.
     * 
     * @return true if authorization was granted immediately, false if consent is needed
     */
    private suspend fun requestDriveAuthorization(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Requesting Drive authorization")
            
            authorizationResolution = null
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            
            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                Log.d(TAG, "Drive authorization requires user consent - pendingIntent available")
                _needsDriveConsent.value = true
                false
            } else {
                authorizationResolution = null
                val accessToken = authorizationResult?.accessToken
                val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
                val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
                Log.i(
                    TAG,
                    "Drive authorization granted, accessToken present: ${accessToken != null}, " +
                        "drive.file scope granted: $hasDriveScope, scopes: $grantedScopes"
                )
                _needsDriveConsent.value = false
                
                if (accessToken == null || !hasDriveScope) {
                    // This is the classic cause of "Permission denied. Check Drive access.":
                    // the token was issued but does not carry drive.file, so the Drive API
                    // answers 403. Do not persist a broken token.
                    Log.e(
                        TAG,
                        "Drive authorization did not grant the drive.file scope. " +
                            "Ensure the OAuth consent screen for client ${BuildConfig.GOOGLE_WEB_CLIENT_ID} " +
                            "includes https://www.googleapis.com/auth/drive.file"
                    )
                    authorizationResult = null
                    authorizationResolution = null
                    tokenStorage.clearToken()
                    return@withContext false
                }
                
                // Persist the access token for background workers
                tokenStorage.saveAccessToken(accessToken)
                Log.d(TAG, "Access token persisted to secure storage")
                
                true
            }
        } catch (e: ApiException) {
            // GMS may throw ApiException carrying the consent pendingIntent
            val resolution = e.status.resolution
            if (resolution != null) {
                authorizationResult = null
                authorizationResolution = resolution
                Log.d(TAG, "Drive authorization requires user consent (ApiException) - pendingIntent available")
                _needsDriveConsent.value = true
                false
            } else {
                authorizationResult = null
                authorizationResolution = null
                _needsDriveConsent.value = false
                tokenStorage.clearToken()
                Log.e(TAG, "Failed to request Drive authorization (${e.status.statusCode}: ${e.status.statusMessage})", e)
                false
            }
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to request Drive authorization", e)
            false
        }
    }
    
    /**
     * Called after user completes the consent flow via pendingIntent.
     * Re-requests authorization to get the access token.
     */
    suspend fun completeConsentFlow(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Completing consent flow, re-requesting authorization")
            _needsDriveConsent.value = false
            authorizationResolution = null
            
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()

            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                _needsDriveConsent.value = true
                Log.w(TAG, "Drive consent still requires user resolution after consent flow")
                return@withContext false
            }
            
            val accessToken = authorizationResult?.accessToken
            val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
            val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
            val hasToken = accessToken != null && hasDriveScope
            Log.i(TAG, "Consent flow completed, accessToken present: $hasToken, drive.file scope: $hasDriveScope")
            
            // Persist the access token for background workers
            if (accessToken != null && hasDriveScope) {
                tokenStorage.saveAccessToken(accessToken)
                Log.d(TAG, "Access token persisted to secure storage")
            } else {
                authorizationResult = null
                authorizationResolution = null
                tokenStorage.clearToken()
            }
            
            hasToken
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to complete consent flow (${e.status.statusCode}: ${e.status.statusMessage})", e)
            false
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            tokenStorage.clearToken()
            Log.e(TAG, "Failed to complete consent flow", e)
            false
        }
    }
    
    /**
     * Get the authorization result for Drive API access.
     * May return null if not authorized yet.
     */
    fun getAuthorizationResult(): AuthorizationResult? = authorizationResult

    /**
     * Build the Drive authorization request.
     *
     * Binds the token to the OAuth Web client (GOOGLE_WEB_CLIENT_ID) and to the
     * account the user signed in with, so Google Play services issues an access
     * token against the consent screen that lists the `drive.file` scope.
     *
     * A Drive authorization request must never fall back to an implicit OAuth
     * client: that can mint a token without the required `drive.file` scope and
     * later surface as a misleading Drive permission failure.
     */
    private fun buildDriveAuthRequest(): AuthorizationRequest {
        val webClientId = requireNotNull(configuredWebClientId()) {
            "GOOGLE_WEB_CLIENT_ID is required for Google Drive authorization"
        }

        val builder = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(DRIVE_SCOPE))

        // Pin the authorization to the exact account used for sign-in.
        _currentAccount.value?.let { account ->
            builder.setAccount(Account(account.email, "com.google"))
        }

        // Bind every Drive token request to the configured OAuth Web client.
        builder.requestOfflineAccess(webClientId)

        return builder.build()
    }
    
    /**
     * Check if Drive authorization requires user consent.
     * Returns the pending intent if consent is needed.
     */
    fun getDriveAuthorizationPendingIntent(): PendingIntent? =
        authorizationResolution ?: authorizationResult?.pendingIntent
    
    /**
     * Update authorization result after user consent.
     */
    fun updateAuthorizationResult(result: AuthorizationResult) {
        authorizationResult = result
        authorizationResolution = result.pendingIntent.takeIf { result.hasResolution() }
        _needsDriveConsent.value = result.hasResolution()
        Log.i(TAG, "Authorization result updated, accessToken present: ${result.accessToken != null}")

        val hasDriveScope = result.grantedScopes.orEmpty().any { it.contains(DriveScopes.DRIVE_FILE) }
        if (!result.hasResolution() && result.accessToken != null && hasDriveScope) {
            tokenStorage.saveAccessToken(result.accessToken!!)
            Log.d(TAG, "Authorization result token persisted to secure storage")
        } else if (!result.hasResolution()) {
            authorizationResult = null
            authorizationResolution = null
            tokenStorage.clearToken()
        }
    }
    
    /**
     * Get access token for Google Drive API calls.
     * Returns null if not signed in or not authorized.
     * 
     * Falls back to persisted token storage if in-memory token is unavailable.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            // First, try to get from in-memory authorization result
            val result = authorizationResult
            if (result != null && !result.hasResolution()) {
                val token = result.accessToken
                val hasDriveScope = result.grantedScopes.orEmpty()
                    .any { it.contains(DriveScopes.DRIVE_FILE) }
                if (token != null && hasDriveScope) {
                    return@withContext token
                }
                if (token != null && !hasDriveScope) {
                    Log.w(TAG, "Discarding in-memory token without drive.file scope")
                    authorizationResult = null
                    authorizationResolution = null
                    tokenStorage.clearToken()
                }
            }
            
            // Fallback to persisted token storage (for background workers)
            val persistedToken = tokenStorage.getAccessToken()
            if (persistedToken != null) {
                // Refresh before the usual one-hour OAuth lifetime is reached.
                // If Google says consent is required, or the token has crossed our
                // hard-expiry threshold, never hand the old token to Drive.
                if (tokenStorage.isTokenStale()) {
                    Log.w(TAG, "Persisted token is stale, attempting silent refresh")
                    if (refreshAccessToken()) {
                        val freshToken = tokenStorage.getAccessToken()
                        if (freshToken != null) {
                            Log.d(TAG, "Got fresh token via silent refresh")
                            return@withContext freshToken
                        }
                    }

                    if (_needsDriveConsent.value || tokenStorage.isTokenExpired()) {
                        Log.w(TAG, "Stale Drive token cannot be reused; authorization is required")
                        tokenStorage.clearToken()
                        return@withContext null
                    }

                    // A transient Play Services failure may happen slightly before
                    // the token's normal expiry. Keep that previously scope-validated
                    // token only while it remains below the hard-expiry threshold;
                    // Drive will still reject/refresh it on a real 401.
                    Log.w(TAG, "Silent refresh failed; temporarily using cached Drive token")
                    return@withContext persistedToken
                }
                Log.d(TAG, "Using persisted token from storage")
                return@withContext persistedToken
            }
            
            Log.w(TAG, "No access token available (memory or storage)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token", e)
            null
        }
    }

    /**
     * Force refresh of the access token.
     * Call this when a 401 Unauthorized error is encountered.
     */
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Refreshing access token")
            
            // Re-authorize to get a fresh token. Clear the old result before
            // awaiting so an exception cannot leave a stale token readable.
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            
            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                Log.d(TAG, "Refresh requires user consent")
                _needsDriveConsent.value = true
                false
            } else {
                authorizationResolution = null
                _needsDriveConsent.value = false
                val accessToken = authorizationResult?.accessToken
                val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
                val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
                val hasToken = accessToken != null && hasDriveScope
                Log.i(TAG, "Token refreshed successfully: $hasToken (drive.file scope: $hasDriveScope)")
                
                // Persist the refreshed token
                if (accessToken != null && hasDriveScope) {
                    tokenStorage.saveAccessToken(accessToken)
                    Log.d(TAG, "Refreshed token persisted to secure storage")
                } else {
                    authorizationResult = null
                    authorizationResolution = null
                    tokenStorage.clearToken()
                }
                
                hasToken
            }
        } catch (e: ApiException) {
            Log.w(TAG, "Refresh failed (${e.status.statusCode}: ${e.status.statusMessage})", e)
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            false
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            Log.e(TAG, "Failed to refresh token", e)
            false
        }
    }
    
    /**
     * Drop the in-memory authorization so the next Drive call re-authorizes.
     * Called when Google returns HTTP 403 (the token lacks the required scope).
     */
    fun invalidateAuthorization() {
        authorizationResult = null
        authorizationResolution = null
        _needsDriveConsent.value = false
    }
    
    /**
     * Drop the persisted access token. Called when a token is known to be
     * broken (wrong scope / denied permissions).
     */
    fun clearPersistedAccessToken() {
        tokenStorage.clearToken()
    }
    
    /**
     * Sign out and clear all credentials.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Signing out")

        // Credential Manager cleanup is best effort. It must not gate the local
        // credential/token cleanup below, otherwise one provider exception can
        // leave Tempo looking signed in with stale Drive credentials.
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Credential Manager cleanup failed during sign out", e)
        }

        try {
            tokenStorage.clearAll()
            Log.d(TAG, "Cleared persisted tokens from secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted Google credentials", e)
        } finally {
            authorizationResult = null
            authorizationResolution = null
            _currentAccount.value = null
            _isSignedIn.value = false
            _needsDriveConsent.value = false
        }

        Log.i(TAG, "Finished Google sign out cleanup")
    }
    
    /**
     * Restore session on app launch.
     * Attempts to silently sign in with previously authorized account.
     * 
     * @param activity The Activity context REQUIRED for Credential Manager UI
     */
    suspend fun restoreSession(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        val webClientId = configuredWebClientId()
        if (webClientId == null) {
            Log.w(TAG, "Skipping session restore: GOOGLE_WEB_CLIENT_ID is blank")
            return@withContext false
        }

        try {
            Log.d(TAG, "Attempting to restore session")
            
            val credentialManager = CredentialManager.create(activity)
            
            // Session restore intentionally uses GetGoogleIdOption so Credential
            // Manager can restrict the request to previously authorized accounts.
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(true) // Only previously authorized accounts
                .setAutoSelectEnabled(true) // Auto-select if only one account
                .build()
            
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            
            val response = credentialManager.getCredential(activity, request)
            val result = handleSignInResponse(response)
            
            result is GoogleSignInResult.Success
        } catch (e: NoCredentialException) {
            Log.d(TAG, "No saved credentials to restore")
            false
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Session restore cancelled")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore session", e)
            false
        }
    }
    
    /**
     * Attempt silent session restore without showing UI for background operations (e.g. WorkManager).
     * Restores account info from encrypted storage and attempts silent re-authorization with Play Services.
     */
    suspend fun restoreSessionSilently(): Boolean = withContext(Dispatchers.IO) {
        // Step 1: Check if a drive.file-scoped session is already active in memory.
        val activeAuthorization = authorizationResult
        val activeHasDriveScope = activeAuthorization?.grantedScopes.orEmpty()
            .any { it.contains(DriveScopes.DRIVE_FILE) }
        if (_isSignedIn.value && activeAuthorization?.accessToken != null && activeHasDriveScope) {
            Log.d(TAG, "Session already active in memory")
            return@withContext true
        }

        // Never attempt background authorization against an implicit OAuth client.
        // Builds without the configured Web client ID must require an interactive,
        // correctly configured build instead of silently minting the wrong token.
        if (configuredWebClientId() == null) {
            Log.w(TAG, "Skipping silent session restore: GOOGLE_WEB_CLIENT_ID is blank")
            return@withContext false
        }
        
        // Step 2: Try to restore from encrypted storage
        val storageStatus = tokenStorage.getStorageStatus()
        Log.d(TAG, "Token storage status: $storageStatus")
        
        if (!tokenStorage.hasAccountInfo()) {
            Log.d(TAG, "No stored account info - user needs to sign in via UI")
            return@withContext false
        }
        
        // Restore account info to state
        val storedAccount = tokenStorage.getStoredAccount()
        if (storedAccount != null) {
            _currentAccount.value = storedAccount
            Log.d(TAG, "Restored account info from storage: ${storedAccount.email}")
        }
        
        // Step 3: Attempt silent re-authorization with Google Play Services
        // This works because Google Play Services caches the authorization
        try {
            Log.d(TAG, "Attempting silent re-authorization with Google Play Services")
            
            authorizationResult = null
            authorizationResolution = null
            authorizationResult = authorizationClient.authorize(buildDriveAuthRequest()).await()
            
            // Check if we got a token without needing user consent
            if (authorizationResult?.hasResolution() == true) {
                authorizationResolution = authorizationResult?.pendingIntent
                _needsDriveConsent.value = true
                Log.d(TAG, "Re-authorization requires user consent - cannot complete silently")
                return@withContext false
            }
            authorizationResolution = null
            _needsDriveConsent.value = false
            
            val accessToken = authorizationResult?.accessToken
            val grantedScopes = authorizationResult?.grantedScopes.orEmpty()
            val hasDriveScope = grantedScopes.any { it.contains(DriveScopes.DRIVE_FILE) }
            if (accessToken != null && hasDriveScope) {
                // Successfully got a fresh token
                tokenStorage.saveAccessToken(accessToken)
                _isSignedIn.value = true
                Log.i(TAG, "Successfully restored session silently for ${storedAccount?.email}")
                return@withContext true
            }
            
            Log.w(TAG, "Re-authorization completed but no drive.file-scoped access token received (scopes: $grantedScopes)")
            authorizationResult = null
            authorizationResolution = null
            tokenStorage.clearToken()
            return@withContext false
            
        } catch (e: ApiException) {
            authorizationResult = null
            authorizationResolution = e.status.resolution
            _needsDriveConsent.value = authorizationResolution != null
            Log.w(
                TAG,
                "Silent re-authorization failed (${e.status.statusCode}: ${e.status.statusMessage})",
                e
            )

            // A resolution is an explicit instruction from Google that the user
            // must consent again. Do not mask it with a cached token.
            if (authorizationResolution != null) {
                tokenStorage.clearToken()
                return@withContext false
            }

            // For a non-consent Play Services failure, a still-valid token that
            // Tempo previously persisted only after verifying drive.file scope may
            // remain usable; Drive itself will reject it if Google invalidated it.
            if (tokenStorage.hasToken() && !tokenStorage.isTokenExpired()) {
                Log.d(TAG, "Using previously validated cached Drive token")
                _isSignedIn.value = true
                return@withContext true
            }
            return@withContext false
        } catch (e: Exception) {
            authorizationResult = null
            authorizationResolution = null
            _needsDriveConsent.value = false
            Log.w(TAG, "Silent re-authorization failed: ${e.message}", e)

            // Non-Google failures (for example a transient local Play Services
            // issue) may still use a non-expired token that was previously saved
            // only after verifying drive.file scope.
            if (tokenStorage.hasToken() && !tokenStorage.isTokenExpired()) {
                Log.d(TAG, "Using previously validated cached Drive token")
                _isSignedIn.value = true
                return@withContext true
            }
            return@withContext false
        }
    }
}
