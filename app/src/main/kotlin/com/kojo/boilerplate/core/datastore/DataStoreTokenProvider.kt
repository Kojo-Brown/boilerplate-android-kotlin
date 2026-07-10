package com.kojo.boilerplate.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.di.ApplicationScope
import com.kojo.boilerplate.core.network.TokenProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreTokenProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : TokenProvider {

    private val cache = AtomicReference<AuthTokens?>(null)
    private val loaded = AtomicBoolean(false)

    val tokensFlow: Flow<AuthTokens?> = dataStore.data.map { prefs ->
        val access = prefs[KEY_ACCESS_TOKEN]
        val refresh = prefs[KEY_REFRESH_TOKEN]
        if (access != null && refresh != null) AuthTokens(access, refresh) else null
    }

    private fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            val stored = runBlocking(ioDispatcher) {
                dataStore.data.first().let { prefs ->
                    val access = prefs[KEY_ACCESS_TOKEN]
                    val refresh = prefs[KEY_REFRESH_TOKEN]
                    if (access != null && refresh != null) AuthTokens(access, refresh) else null
                }
            }
            cache.set(stored)
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
        cache.set(AuthTokens(accessToken, refreshToken))
        loaded.set(true)
        appScope.launch(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_ACCESS_TOKEN] = accessToken
                prefs[KEY_REFRESH_TOKEN] = refreshToken
            }
        }
    }

    override fun clearTokens() {
        cache.set(null)
        appScope.launch(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs.remove(KEY_ACCESS_TOKEN)
                prefs.remove(KEY_REFRESH_TOKEN)
            }
        }
    }
}
