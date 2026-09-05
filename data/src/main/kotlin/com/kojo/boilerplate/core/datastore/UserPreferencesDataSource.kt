package com.kojo.boilerplate.core.datastore

import androidx.datastore.core.DataStore
import com.kojo.boilerplate.core.common.preferences.SyncPolicy
import com.kojo.boilerplate.core.common.preferences.SyncPreferences
import com.kojo.boilerplate.core.common.preferences.UserPreferences
import com.kojo.boilerplate.core.datastore.proto.SyncPolicyProto
import com.kojo.boilerplate.core.datastore.proto.SyncPreferencesProto
import com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The app's typed preferences: reads as a [Flow] of [UserPreferences], writes as one call per
 * decision the user made.
 *
 * ### Why the generated types stop here
 *
 * `:core:datastore-proto` is an `implementation` dependency of this module, so nothing outside
 * `:data` can name a [UserPreferencesProto] — and this class is why that costs nothing. The
 * mapping in this file is the only place the wire format is understood: a field number, an
 * `UNSPECIFIED` enum constant and a `0` timestamp meaning "never" all end at the boundary.
 *
 * ### Why there is no interface
 *
 * There is nothing to substitute. A fake would reimplement DataStore's serialisation and
 * concurrency, which is the part worth testing, so the tests build a real store over a
 * temporary directory instead — see `UserPreferencesDataSourceTest`. `docs/solid.md` finding 3
 * is about repositories injected by their concrete type with an abstraction that *would* mean
 * something; this is a data source over a file, and inverting it would only move the file.
 *
 * ### Errors
 *
 * A file that cannot be parsed is replaced with the defaults by
 * [userPreferencesCorruptionHandler], so no reader ever sees a `CorruptionException`. An
 * `IOException` — the app's own files directory unreadable or full — is deliberately *not*
 * caught: it is not a state the user's settings can be recovered from, and swallowing it here
 * would turn "the device is out of space" into "the app quietly forgot your preferences".
 */
@Singleton
class UserPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<UserPreferencesProto>,
) {

    /**
     * The current preferences, re-emitting on every write — including writes made by another
     * process-local caller between two of yours.
     *
     * Cold: each collector gets its own read of the file. That is DataStore's own behaviour and
     * is left alone, because the consumers of this are `stateIn`-shaped view models that share
     * one upstream anyway.
     */
    val preferences: Flow<UserPreferences> = dataStore.data.map { it.toModel() }

    /**
     * Replaces the whole sync block in one write.
     *
     * The nested message is what makes this one write rather than three. A user who switches
     * to [SyncPolicy.AnyNetwork] and turns off "only while charging" in the same settings
     * screen must not be able to leave the file in a state that was never on screen — and with
     * three independent preference keys that is exactly what a crash between two `edit` calls
     * produces.
     */
    suspend fun setSyncPreferences(sync: SyncPreferences) {
        dataStore.updateData { current ->
            current.toBuilder().setSync(sync.toProto()).build()
        }
    }

    /**
     * Records that a sync finished cleanly, leaving the rest of the sync block alone.
     *
     * Written as a read-modify-write *inside* [DataStore.updateData] rather than as a
     * `setSyncPreferences(observed.copy(lastSuccessfulSync = at))` from a previously observed
     * value. `updateData` serialises its transforms and hands each one the current contents, so
     * a policy change landing between a caller's last read and this write survives it. The
     * `copy` version reads perfectly well and silently reverts that change: the background sync
     * is the most frequent writer here, so it is also the most likely to be holding a stale
     * copy of everything else.
     */
    suspend fun recordSuccessfulSync(at: Instant) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setSync(
                    current.sync.toBuilder()
                        .setLastSuccessfulSyncEpochMillis(at.toEpochMilli())
                        .build(),
                )
                .build()
        }
    }

    /** Records that the user has been through onboarding. A one-way transition. */
    suspend fun markOnboardingComplete() {
        dataStore.updateData { current ->
            current.toBuilder().setOnboardingComplete(true).build()
        }
    }
}

// The boundary. Everything below understands the schema; nothing above it does.

private fun UserPreferencesProto.toModel(): UserPreferences = UserPreferences(
    sync = SyncPreferences(
        policy = sync.policy.toModel(),
        onlyWhileCharging = sync.onlyWhileCharging,
        // 0 is the schema's "never", and it is also a real instant — 1 January 1970 — so it
        // has to be turned into an absence here rather than carried up as a number that
        // formats and compares like any other time.
        lastSuccessfulSync = sync.lastSuccessfulSyncEpochMillis
            .takeIf { it != NEVER_SYNCED }
            ?.let(Instant::ofEpochMilli),
    ),
    onboardingComplete = onboardingComplete,
)

private fun SyncPreferences.toProto(): SyncPreferencesProto = SyncPreferencesProto.newBuilder()
    .setPolicy(policy.toProto())
    .setOnlyWhileCharging(onlyWhileCharging)
    .setLastSuccessfulSyncEpochMillis(lastSuccessfulSync?.toEpochMilli() ?: NEVER_SYNCED)
    .build()

/**
 * `UNSPECIFIED` is a device that has never chosen, and `UNRECOGNIZED` is a value written by a
 * build of this app that knew a policy this one does not — a downgrade, or a beta channel on
 * the same device. Both resolve to the default rather than throwing: a preferences file is not
 * worth a crash, and the next write from this build replaces the unknown constant anyway.
 */
private fun SyncPolicyProto.toModel(): SyncPolicy = when (this) {
    SyncPolicyProto.SYNC_POLICY_PROTO_UNMETERED_ONLY -> SyncPolicy.UnmeteredOnly
    SyncPolicyProto.SYNC_POLICY_PROTO_ANY_NETWORK -> SyncPolicy.AnyNetwork
    SyncPolicyProto.SYNC_POLICY_PROTO_UNSPECIFIED,
    SyncPolicyProto.UNRECOGNIZED,
    -> SyncPolicy.Default
}

private fun SyncPolicy.toProto(): SyncPolicyProto = when (this) {
    SyncPolicy.UnmeteredOnly -> SyncPolicyProto.SYNC_POLICY_PROTO_UNMETERED_ONLY
    SyncPolicy.AnyNetwork -> SyncPolicyProto.SYNC_POLICY_PROTO_ANY_NETWORK
}

/** The schema's sentinel for "no sync has ever completed". */
private const val NEVER_SYNCED = 0L
