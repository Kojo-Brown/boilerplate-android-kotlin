package com.kojo.boilerplate.core.database.converter

import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserFieldSetConverterTest {

    private val converter = UserFieldSetConverter()

    @Test
    fun `every set round-trips`() {
        val sets = listOf(
            emptySet(),
            setOf(UserField.DISPLAY_NAME),
            setOf(UserField.EMAIL),
            setOf(UserField.AVATAR_URL),
            setOf(UserField.DISPLAY_NAME, UserField.EMAIL),
            UserField.entries.toSet(),
        )

        sets.forEach { fields ->
            assertEquals(fields, converter.toUserFieldSet(converter.fromUserFieldSet(fields)))
        }
    }

    /**
     * The empty set is the common case — it is what every row off the network carries — and
     * the empty string is the column default `MIGRATION_1_2` fills existing rows with, so this
     * pairing is what makes the migration write no data.
     */
    @Test
    fun `the empty set is the empty string`() {
        assertEquals("", converter.fromUserFieldSet(emptySet()))
        assertEquals(emptySet<UserField>(), converter.toUserFieldSet(""))
    }

    /** Names, not ordinals: reordering the enum must not silently repoint existing rows. */
    @Test
    fun `fields are stored by name`() {
        assertEquals("DISPLAY_NAME,EMAIL", converter.fromUserFieldSet(setOf(UserField.DISPLAY_NAME, UserField.EMAIL)))
    }

    /**
     * A field removed in a later version of the app, read back out of a database written by an
     * earlier one. Throwing would make the row unreadable on Room's query thread, and the
     * failure would land on whatever screen happened to be observing; dropping it degrades to
     * "this field is not pending", which at worst lets a stale edit stop winning conflicts.
     */
    @Test
    fun `an unrecognised name is dropped rather than throwing`() {
        assertEquals(
            setOf(UserField.EMAIL),
            converter.toUserFieldSet("PHONE_NUMBER,EMAIL"),
        )
        assertEquals(emptySet<UserField>(), converter.toUserFieldSet("PHONE_NUMBER"))
    }

    /** Reading preserves the stored order, which is the enum's declaration order. */
    @Test
    fun `decoding preserves order`() {
        assertEquals(
            listOf(UserField.DISPLAY_NAME, UserField.EMAIL, UserField.AVATAR_URL),
            converter.toUserFieldSet("DISPLAY_NAME,EMAIL,AVATAR_URL").toList(),
        )
    }
}
