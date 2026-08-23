package com.kojo.boilerplate.core.auth

import android.content.Context

interface GoogleAuthRepository {
    suspend fun signIn(activityContext: Context): Result<GoogleUser>
    suspend fun signOut(): Result<Unit>
}
