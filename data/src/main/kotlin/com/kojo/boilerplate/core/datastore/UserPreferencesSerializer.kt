package com.kojo.boilerplate.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto
import java.io.InputStream
import java.io.OutputStream

/**
 * Turns the bytes of `user_preferences.pb` into a [UserPreferencesProto] and back.
 *
 * This is the whole difference between Proto DataStore and Preferences DataStore. A
 * `DataStore<Preferences>` is this same machinery with a serializer for an untyped map, which
 * is why every read there is a key lookup that can miss and a cast that can fail. Handing it a
 * serializer for a generated message moves both of those to compile time.
 *
 * `object` rather than a class: it holds nothing, and the `dataStore` delegate wants one
 * instance for the lifetime of the process.
 */
internal object UserPreferencesSerializer : Serializer<UserPreferencesProto> {

    /**
     * What a device with no preferences file reads.
     *
     * Every field at its proto3 zero value, which is the same thing an *older* file decodes to
     * for a field it does not carry. That is deliberate rather than convenient: it means
     * "fresh install" and "upgraded install" reach [UserPreferencesDataSource] as the same
     * value, and the app's real defaults are resolved from it in exactly one place instead of
     * once per read.
     */
    override val defaultValue: UserPreferencesProto = UserPreferencesProto.getDefaultInstance()

    /**
     * DataStore contracts this to throw [CorruptionException] — and only that — for a file it
     * cannot parse, because that is the signal its corruption handler listens for. Any other
     * exception propagates to the caller and every read of the file fails for as long as the
     * bytes stay on disk.
     *
     * So the catch is narrow on purpose. [InvalidProtocolBufferException] means "these bytes
     * are not this message", which is recoverable by [userPreferencesCorruptionHandler]. A
     * plain `IOException` from the stream means the file could not be read at all, which is
     * not corruption and must not be answered by silently discarding the user's settings.
     */
    override suspend fun readFrom(input: InputStream): UserPreferencesProto =
        try {
            UserPreferencesProto.parseFrom(input)
        } catch (malformed: InvalidProtocolBufferException) {
            throw CorruptionException("user_preferences.pb is not a UserPreferencesProto", malformed)
        }

    /**
     * DataStore writes to a scratch file and renames it over the real one, so a process death
     * part-way through this leaves the previous file intact rather than a half-written one.
     * Nothing here needs to flush or close: the stream belongs to DataStore.
     */
    override suspend fun writeTo(t: UserPreferencesProto, output: OutputStream) = t.writeTo(output)
}
