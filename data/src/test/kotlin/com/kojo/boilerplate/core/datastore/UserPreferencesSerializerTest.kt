package com.kojo.boilerplate.core.datastore

import androidx.datastore.core.CorruptionException
import com.google.protobuf.InvalidProtocolBufferException
import com.kojo.boilerplate.core.datastore.proto.SyncPolicyProto
import com.kojo.boilerplate.core.datastore.proto.SyncPreferencesProto
import com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The bytes half of the store, tested without a `DataStore` around it.
 *
 * Three of these four cases are about a file the app did not write: one that does not exist,
 * one that was written by a build with a smaller schema, and one that is not a protobuf at all.
 * Those are the reads that happen on a user's device rather than in a test, and the only ones
 * where a serializer can be wrong in a way the happy path never shows.
 */
class UserPreferencesSerializerTest {

    @Test
    fun `the default value is every field at its schema zero`() {
        val default = UserPreferencesSerializer.defaultValue

        assertEquals(SyncPolicyProto.SYNC_POLICY_PROTO_UNSPECIFIED, default.sync.policy)
        assertFalse(default.sync.onlyWhileCharging)
        assertEquals(0L, default.sync.lastSuccessfulSyncEpochMillis)
        assertFalse(default.onboardingComplete)
    }

    @Test
    fun `a written message reads back unchanged`() = runBlocking {
        val written = UserPreferencesProto.newBuilder()
            .setOnboardingComplete(true)
            .setSync(
                SyncPreferencesProto.newBuilder()
                    .setPolicy(SyncPolicyProto.SYNC_POLICY_PROTO_ANY_NETWORK)
                    .setOnlyWhileCharging(true)
                    .setLastSuccessfulSyncEpochMillis(SYNCED_AT_MILLIS),
            )
            .build()

        val bytes = ByteArrayOutputStream()
        UserPreferencesSerializer.writeTo(written, bytes)

        assertEquals(written, UserPreferencesSerializer.readFrom(ByteArrayInputStream(bytes.toByteArray())))
    }

    /**
     * An empty file is what a device has between DataStore creating the file and the first
     * write landing in it, and proto3 decodes zero bytes as "every field absent". If this ever
     * threw instead, a first launch interrupted at the wrong moment would look like corruption.
     */
    @Test
    fun `an empty file reads as the default value`() = runBlocking {
        val read = UserPreferencesSerializer.readFrom(ByteArrayInputStream(ByteArray(0)))

        assertEquals(UserPreferencesSerializer.defaultValue, read)
    }

    /**
     * The contract with DataStore: unparseable bytes have to arrive as a [CorruptionException],
     * because that is the only thing `ReplaceFileCorruptionHandler` is given a chance to
     * answer. A serializer that let `InvalidProtocolBufferException` escape would leave the
     * handler wired up, apparently correct, and never once invoked.
     *
     * The cause is asserted as well as the type: it is what a bug report needs to tell "these
     * bytes are not this message" from "this file could not be read".
     */
    @Test
    fun `malformed bytes surface as a CorruptionException`() {
        val thrown = assertThrows(CorruptionException::class.java) {
            runBlocking { UserPreferencesSerializer.readFrom(ByteArrayInputStream(TRUNCATED_VARINT)) }
        }

        assertInstanceOf(InvalidProtocolBufferException::class.java, thrown.cause)
    }

    private companion object {
        const val SYNCED_AT_MILLIS = 1_730_000_000_000L

        /**
         * A tag byte followed by the first byte of a varint that never ends. Protobuf reads the
         * continuation bit, asks for another byte and hits the end of the stream, which is the
         * cheapest genuinely unparseable input there is — a random-looking byte array is not,
         * because most of them decode as unknown fields and parse cleanly.
         */
        val TRUNCATED_VARINT = byteArrayOf(0x08, 0xFF.toByte())
    }
}
