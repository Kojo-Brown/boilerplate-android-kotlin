package com.kojo.boilerplate.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.authTokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_tokens")

internal val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
internal val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
