package com.kojo.boilerplate.core.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.kojo.boilerplate.core.common.safeCall
import javax.inject.Inject

class GoogleAuthRepositoryImpl @Inject constructor(
    private val credentialManager: CredentialManager,
) : GoogleAuthRepository {

    companion object {
        // Replace with the Web Client ID from Google Cloud Console / Firebase
        const val SERVER_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
    }

    // safeCall rather than runCatching: both of these are called from viewModelScope, and
    // that scope is cancelled the moment the user leaves the screen — which for a sign-in
    // prompt is a routine outcome, not a failure. runCatching would turn the cancellation
    // into a Result.failure and the screen would report "Sign-in failed" on its way out.
    override suspend fun signIn(activityContext: Context): Result<GoogleUser> = safeCall {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(SERVER_CLIENT_ID)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val response = credentialManager.getCredential(activityContext, request)
        val credential = response.credential

        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Unexpected credential type: ${credential.type}" }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        GoogleUser(
            id = googleCredential.id,
            email = googleCredential.id,
            displayName = googleCredential.displayName.orEmpty(),
            profilePictureUrl = googleCredential.profilePictureUri?.toString(),
            idToken = googleCredential.idToken,
        )
    }

    override suspend fun signOut(): Result<Unit> = safeCall {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }
}
