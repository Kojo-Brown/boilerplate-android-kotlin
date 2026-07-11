package com.kojo.boilerplate.core.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import javax.inject.Inject

class GoogleAuthRepositoryImpl @Inject constructor(
    private val credentialManager: CredentialManager,
) : GoogleAuthRepository {

    companion object {
        // Replace with the Web Client ID from Google Cloud Console / Firebase
        const val SERVER_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
    }

    override suspend fun signIn(activityContext: Context): Result<GoogleUser> = runCatching {
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

    override suspend fun signOut(): Result<Unit> = runCatching {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }
}
