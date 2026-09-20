package com.kojo.boilerplate.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.kojo.boilerplate.core.coroutines.ApplicationScope
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.network.TokenProvider
import com.kojo.boilerplate.core.security.TokenCipher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Persistent auth tokens, encrypted at rest with a key this process cannot read.
 *
 * What is on disk is `Base64(IV ‖ ciphertext ‖ tag)` under [KEY_ACCESS_TOKEN_CIPHERTEXT] and
 * [KEY_REFRESH_TOKEN_CIPHERTEXT]; the key lives in the Android keystore. See
 * `docs/token-storage.md` for the threat model and `AesGcmTokenCipher` for the format.
 *
 * ### What the cache holds
 *
 * Decrypted tokens, in memory, for the life of the process. That is not a gap in the
 * encryption — a bearer token has to be plaintext at the moment it is written into an
 * `Authorization` header, so it is in this process's heap either way. What encryption at rest
 * buys is that the token does not outlive the process *on disk*, where a backup, an adb pull on
 * a debuggable build, or another app exploiting a directory traversal can reach it. Caching
 * also keeps a keystore round trip off every outbound request.
 *
 * ### Why decryption happens on a blocking read
 *
 * [getAccessToken] and [getRefreshToken] are called by `AuthInterceptor` and
 * `TokenAuthenticator`, which OkHttp invokes synchronously — there is no suspending seam to
 * hand them, which is why `ensureLoaded` bridges with `runBlocking`. That was already true
 * before this class encrypted anything; the decryption simply happens inside the same one-time
 * load. Writes stay fire-and-forget on [appScope] for the same reason, and encryption happens
 * there rather than on the caller's thread.
 *
 * ### Two states that are not "signed out"
 *
 * A store that cannot be decrypted, and a store still holding the plaintext keys an older
 * version of this app wrote, are both handled on the first read — cleared and migrated
 * respectively. `StoredTokens.read` holds that decision, on its own, in a form that runs on a
 * plain JVM.
 */
@Singleton
class DataStoreTokenProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : TokenProvider {

    private val cache = AtomicReference<AuthTokens?>(null)
    private val loaded = AtomicBoolean(false)

    /**
     * The stored tokens as they change.
     *
     * This observes rather than repairs: a store in the [StoredTokenState.Discard] state emits
     * `null` here and is cleaned up by [ensureLoaded], which is the one place that writes. A
     * flow that edited what it read would turn every collector into a writer.
     */
    val tokensFlow: Flow<AuthTokens?> = dataStore.data.map { prefs ->
        when (val state = prefs.storedTokens().read(cipher)) {
            is StoredTokenState.Present -> state.tokens
            StoredTokenState.Discard, StoredTokenState.Empty -> null
        }
    }

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        val state = runBlocking(ioDispatcher) {
            dataStore.data.first().storedTokens().read(cipher)
        }
        when (state) {
            is StoredTokenState.Present -> {
                cache.set(state.tokens)
                // Plaintext left by an older version: adopt it, then write it back encrypted and
                // delete the plaintext. Deliberately not a sign-out — see StoredTokenState.Present.
                if (state.reencrypt) persist(state.tokens)
            }

            StoredTokenState.Discard -> {
                cache.set(null)
                clearPersisted()
            }

            StoredTokenState.Empty -> cache.set(null)
        }
    }

    override fun getAccessToken(): String? {
        ensureLoaded()
        return cache.get()?.accessToken
    }

    override fun getRefreshToken(): String? {
        ensureLoaded()
        return cache.get()?.refreshToken
    }

    override fun updateTokens(accessToken: String, refreshToken: String) {
        val tokens = AuthTokens(accessToken, refreshToken)
        cache.set(tokens)
        // Set before persisting, so a load racing this write cannot overwrite the newer pair in
        // memory with whatever is still on disk.
        loaded.set(true)
        persist(tokens)
    }

    override fun clearTokens() {
        cache.set(null)
        clearPersisted()
    }

    private fun persist(tokens: AuthTokens) {
        appScope.launch(ioDispatcher) {
            // Encryption is on this dispatcher rather than the caller's thread: on a
            // hardware-backed keystore every operation is an IPC to keystored.
            val access = cipher.encrypt(tokens.accessToken)
            val refresh = cipher.encrypt(tokens.refreshToken)
            dataStore.edit { prefs ->
                prefs[KEY_ACCESS_TOKEN_CIPHERTEXT] = access
                prefs[KEY_REFRESH_TOKEN_CIPHERTEXT] = refresh
                prefs.removeLegacyPlaintext()
            }
        }
    }

    private fun clearPersisted() {
        appScope.launch(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs.remove(KEY_ACCESS_TOKEN_CIPHERTEXT)
                prefs.remove(KEY_REFRESH_TOKEN_CIPHERTEXT)
                prefs.removeLegacyPlaintext()
            }
        }
    }
}

/**
 * Every write path removes the plaintext keys, not just the migration.
 *
 * A sign-out or a token refresh on an installation that never happened to read its tokens first
 * would otherwise leave the old plaintext pair sitting in the file indefinitely, beside the
 * encrypted pair that supersedes it.
 */
private fun MutablePreferences.removeLegacyPlaintext() {
    remove(KEY_LEGACY_ACCESS_TOKEN)
    remove(KEY_LEGACY_REFRESH_TOKEN)
}

private fun Preferences.storedTokens(): StoredTokens = StoredTokens(
    accessCiphertext = this[KEY_ACCESS_TOKEN_CIPHERTEXT],
    refreshCiphertext = this[KEY_REFRESH_TOKEN_CIPHERTEXT],
    legacyAccessToken = this[KEY_LEGACY_ACCESS_TOKEN],
    legacyRefreshToken = this[KEY_LEGACY_REFRESH_TOKEN],
)
