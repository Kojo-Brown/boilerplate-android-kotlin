package com.kojo.boilerplate.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto

/**
 * What to do about a `user_preferences.pb` that cannot be parsed: replace it with the defaults.
 *
 * Without a handler, `CorruptionException` propagates out of every read and the app stays
 * broken for as long as the file is on disk — with no way for the user to fix it short of
 * clearing the app's data, which costs them everything else too.
 *
 * That trade is right *here* and would be wrong one file over. What is lost is a set of
 * settings the user can choose again in a few taps. The same handler over a file holding
 * unsynced user data would silently delete work, and the right answer there is to surface the
 * failure. Corruption handling is a decision per store, not a default to copy.
 *
 * Declared as a named property rather than inline below so that the tests exercise the handler
 * the app actually installs. It has to be declared *before* the delegate that reads it: these
 * are top-level properties in one file, so they initialise in source order, and a handler
 * declared underneath would be null at the moment the store is built.
 */
internal val userPreferencesCorruptionHandler = ReplaceFileCorruptionHandler {
    UserPreferencesSerializer.defaultValue
}

/**
 * The typed preferences store, one instance per process.
 *
 * The delegate is what enforces that. DataStore takes an exclusive lock on the file and throws
 * `IllegalStateException: There are multiple DataStores active for the same file` on the
 * second one, so building it anywhere a class is instantiated twice is a crash rather than a
 * duplicated cache. `UserPreferencesModule` is the only reader of this property, and
 * `@Singleton` there is belt to this braces.
 *
 * `.pb` rather than `.preferences_pb`: the extension is free-form, and the two kinds of store
 * in this module hold different formats and should not look alike in a bug report.
 */
internal val Context.userPreferencesDataStore: DataStore<UserPreferencesProto> by dataStore(
    fileName = "user_preferences.pb",
    serializer = UserPreferencesSerializer,
    corruptionHandler = userPreferencesCorruptionHandler,
)
