package com.kojo.boilerplate.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * The auth-token store.
 *
 * The file name is part of the app's security posture rather than an arbitrary label: it is
 * what `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` name when they
 * exclude this store from Android's backup and device-transfer. Renaming [AUTH_TOKEN_STORE]
 * without changing those two files would start shipping encrypted tokens off the device again —
 * silently, since the ciphertext restores perfectly well and simply cannot be decrypted on the
 * far side, where the keystore key never followed it. `TokenStorageContractTest` fails the build
 * if the three ever disagree.
 */
const val AUTH_TOKEN_STORE = "auth_tokens"

val Context.authTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AUTH_TOKEN_STORE,
)

internal val KEY_ACCESS_TOKEN_CIPHERTEXT = stringPreferencesKey("access_token_ciphertext")
internal val KEY_REFRESH_TOKEN_CIPHERTEXT = stringPreferencesKey("refresh_token_ciphertext")

/**
 * The keys a previous version of this app wrote its tokens into, in plaintext.
 *
 * Kept so that an installation carrying them can be migrated once and then cleaned — see
 * `StoredTokens.read`. Nothing in this module may ever *write* to either of them again, which
 * is what `TokenStorageContractTest` asserts: the only operation these two appear in is
 * `remove`.
 */
internal val KEY_LEGACY_ACCESS_TOKEN = stringPreferencesKey("access_token")
internal val KEY_LEGACY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
