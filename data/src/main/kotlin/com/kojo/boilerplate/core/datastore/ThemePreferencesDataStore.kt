package com.kojo.boilerplate.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.themePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

internal val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
