package com.kojo.boilerplate.core.common.preferences

import java.time.Instant

/**
 * The app's preferences, as the app talks about them.
 *
 * These are deliberately *not* the generated protobuf types. `:core:datastore-proto` owns the
 * on-disk schema and `:data` maps between the two, so a `.proto` field number, an
 * `UNSPECIFIED` enum constant and a sentinel `0` timestamp stay facts about a file rather than
 * things a view model has to know. Two consequences are the point of the split:
 *
 *  - Absent and default are the same value on the wire and different values here. A file that
 *    predates [SyncPreferences.policy] decodes to the proto's zero constant; it arrives here as
 *    the default this file declares, and nothing downstream can tell — or has to.
 *  - "Never synced" is `null`, not `0`. Epoch millis 0 is a real [Instant] — 1 January 1970 —
 *    so a model that carried the sentinel through would let "never" be formatted, compared and
 *    subtracted from as though it were a time.
 *
 * No Compose stability annotation: this module is compiled without the Compose plugin and these
 * are not view state today. A screen that puts one of these in a `UiState` should hold that
 * `UiState` to the contract in `StabilityContractTest` rather than annotate these in passing.
 */
data class UserPreferences(
    val sync: SyncPreferences = SyncPreferences(),
    val onboardingComplete: Boolean = false,
)

/**
 * What the background sync is allowed to do. Read and written as one value, which is why it is
 * a nested message on disk rather than three loose keys.
 *
 * The defaults are the ones a fresh install gets, and they are declared here rather than in the
 * schema because proto3 has no defaults: every scalar decodes to its zero value, so "the user
 * has not chosen" and "the user chose the thing that happens to be `false`" are the same bytes.
 * Naming the default in exactly one place is what keeps the two from being answered differently
 * in two readers.
 */
data class SyncPreferences(
    /** Defaults to [SyncPolicy.Default]: an app does not spend a user's data by default. */
    val policy: SyncPolicy = SyncPolicy.Default,
    /** Defaults to `false`. Waiting for a charger can delay a sync indefinitely. */
    val onlyWhileCharging: Boolean = false,
    /** When the last sync completed without error, or `null` when none ever has. */
    val lastSuccessfulSync: Instant? = null,
)

/** How much of the user's connection the background sync may use. */
enum class SyncPolicy {
    /** Wi-Fi and other connections the user is not billed by the byte for. */
    UnmeteredOnly,

    /** Any connection, including a metered mobile one. */
    AnyNetwork,

    ;

    companion object {
        /**
         * What a device that has never chosen gets.
         *
         * Named rather than repeated, because it is answered in two places that must agree:
         * the default argument on [SyncPreferences.policy], and the mapping in `:data` that
         * turns the schema's `UNSPECIFIED` constant into a policy. Two literals there is a
         * bug the compiler cannot see.
         */
        val Default: SyncPolicy = UnmeteredOnly
    }
}
