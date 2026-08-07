package com.kojo.boilerplate.core.di

import android.content.Context
import android.net.ConnectivityManager
import com.kojo.boilerplate.core.network.connectivity.ConnectivityManagerNetworkMonitor
import com.kojo.boilerplate.core.network.connectivity.NetworkMonitor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectivityModule {

    /**
     * Swap this binding for a fake to develop a screen's offline state without touching
     * aeroplane mode. Everything downstream depends on the interface.
     */
    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(impl: ConnectivityManagerNetworkMonitor): NetworkMonitor

    companion object {

        /**
         * Injected rather than resolved from a `Context` inside the monitor, so the monitor's
         * unit tests can drive a mocked manager instead of standing up a framework service.
         *
         * `getSystemService` is declared nullable because a `Context` need not carry every
         * service; the application context on any device that can run this app carries this
         * one, so a `null` here is a broken platform rather than a case to degrade into, and
         * failing at injection says so at the point of the fault.
         */
        @Provides
        @Singleton
        fun provideConnectivityManager(
            @ApplicationContext context: Context,
        ): ConnectivityManager =
            requireNotNull(context.getSystemService(ConnectivityManager::class.java)) {
                "ConnectivityManager is not available from the application context"
            }
    }
}
