package com.kojo.boilerplate.core.database.converter

import androidx.room.TypeConverter
import com.kojo.boilerplate.core.domain.sync.conflict.UserField

/** What separates two field names in the stored string. */
private const val SEPARATOR = ","

/**
 * Stores `UserEntity.locallyChanged` as a comma-separated list of [UserField] names.
 *
 * ## Why a string column rather than a join table
 *
 * The set is at most three elements, it is only ever read and written whole alongside its row,
 * and nothing ever queries by it. A join table would be the right answer to "which rows have a
 * pending display-name edit?", which is a question this app does not ask and would not ask of
 * three flags.
 *
 * ## Why names and not ordinals
 *
 * An ordinal is one byte shorter and one refactor away from being wrong. Reordering the
 * constants in [UserField] — or inserting one, which is the ordinary way an enum grows — is a
 * source change with no compile error and no migration, and every row already in the database
 * would silently start meaning a different field. Names cost a few bytes per row and survive
 * a reorder.
 *
 * ## An unrecognised name is dropped, not thrown
 *
 * A name that no longer maps to a constant is a field that was removed in a later version of
 * the app, read back out of a database written by an earlier one. Throwing there would make
 * the row unreadable — and it is Room's query thread that would see it, so the failure lands
 * on whatever screen happened to be observing. Dropping it degrades to "this field is not
 * pending", which is the conservative reading: the worst case is that a stale local edit to a
 * field the app no longer has stops winning conflicts.
 */
class UserFieldSetConverter {

    @TypeConverter
    fun fromUserFieldSet(fields: Set<UserField>): String =
        fields.joinToString(SEPARATOR) { it.name }

    @TypeConverter
    fun toUserFieldSet(stored: String): Set<UserField> {
        if (stored.isEmpty()) return emptySet()

        // `filterNotNull` after a lookup that can miss, rather than `valueOf`, which throws.
        val byName = UserField.entries.associateBy { it.name }
        return stored.split(SEPARATOR).mapNotNullTo(LinkedHashSet()) { byName[it] }
    }
}
