package com.kojo.boilerplate.core.database.migration

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room's own bookkeeping table. Excluded from every snapshot because its single row holds the
 * identity hash of the schema, which differs between "created at version 3" and "migrated to
 * version 3" only when something is wrong — and Room checks that itself, on open, far more
 * precisely than a table comparison could.
 */
private const val ROOM_MASTER_TABLE = "room_master_table"

private const val TABLE_QUERY =
    "SELECT name FROM sqlite_master WHERE type = 'table' " +
        "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' " +
        "AND name != '$ROOM_MASTER_TABLE' ORDER BY name"

/** `PRAGMA index_list` reports this origin for an index a `CREATE INDEX` statement made. */
private const val ORIGIN_CREATE_INDEX = "c"

/**
 * What a live SQLite file actually contains, read back through `PRAGMA`.
 *
 * This is the shape a migration is asserted against. Comparing the `sql` column of
 * `sqlite_master` instead would be simpler and wrong: `ALTER TABLE … ADD COLUMN` appends the new
 * column's definition to the statement the table was *originally* created with, so a migrated
 * `users` carries the version-1 text — no `IF NOT EXISTS`, the old column ordering — while a
 * freshly created one carries whatever Room generates today. The two databases are identical to
 * SQLite and differ in most bytes of that string.
 *
 * Columns are sorted by name rather than kept in declaration order, for the same reason in
 * reverse: reordering the properties of an `@Entity` changes the order a fresh install creates
 * them in and cannot change the order an already-upgraded database has. Room's own `TableInfo`
 * comparison ignores column order, so a snapshot that did not would fail on a difference Room
 * accepts.
 */
data class SchemaSnapshot(val tables: List<TableSnapshot>) {

    /** A rendering, so an assertion failure names the difference instead of a hash code. */
    override fun toString(): String = tables.joinToString("\n") { it.render() }

    companion object {
        fun of(db: SupportSQLiteDatabase): SchemaSnapshot =
            SchemaSnapshot(db.column(TABLE_QUERY).map { table -> tableSnapshot(db, table) })
    }
}

/** One table's columns and its non-implicit indices. */
data class TableSnapshot(
    val name: String,
    val columns: List<ColumnSnapshot>,
    val indices: List<IndexSnapshot>,
) {
    /**
     * Deliberately not `buildString`: inside its lambda the receiver is a `StringBuilder`, and
     * `CharSequence.indices` — the extension giving a string's index range — wins over this
     * class's own [indices] property, so the body silently iterated an `IntRange`.
     */
    fun render(): String =
        (listOf("TABLE $name") + columns.map { "  ${it.render()}" } + indices.map { "  ${it.render()}" })
            .joinToString("\n")
}

/**
 * @property primaryKeyPosition `0` for a column outside the primary key, otherwise its 1-based
 *   position within it — which is what SQLite reports and what distinguishes the two halves of
 *   a composite key from each other.
 */
data class ColumnSnapshot(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKeyPosition: Int,
) {
    fun render(): String =
        "$name $type notNull=$notNull default=${defaultValue ?: "<none>"} pk=$primaryKeyPosition"
}

/**
 * An index Room declared. Indices SQLite creates for a primary key are skipped: their origin is
 * `pk` rather than [ORIGIN_CREATE_INDEX], and they are a consequence of the column list that is
 * already being compared.
 */
data class IndexSnapshot(val name: String, val unique: Boolean, val columns: List<String>) {
    fun render(): String = "INDEX $name unique=$unique (${columns.joinToString(", ")})"
}

private fun tableSnapshot(db: SupportSQLiteDatabase, table: String): TableSnapshot = TableSnapshot(
    name = table,
    columns = columnSnapshots(db, table),
    indices = indexSnapshots(db, table),
)

private fun columnSnapshots(db: SupportSQLiteDatabase, table: String): List<ColumnSnapshot> =
    db.rows("PRAGMA table_info(`$table`)") { cursor ->
        ColumnSnapshot(
            name = cursor.string("name"),
            type = cursor.string("type"),
            notNull = cursor.int("notnull") != 0,
            defaultValue = cursor.stringOrNull("dflt_value"),
            primaryKeyPosition = cursor.int("pk"),
        )
    }.sortedBy { it.name }

private fun indexSnapshots(db: SupportSQLiteDatabase, table: String): List<IndexSnapshot> =
    db.rows("PRAGMA index_list(`$table`)") { cursor ->
        Triple(cursor.string("name"), cursor.int("unique") != 0, cursor.string("origin"))
    }
        .filter { (_, _, origin) -> origin == ORIGIN_CREATE_INDEX }
        .map { (name, unique, _) -> IndexSnapshot(name, unique, db.indexColumns(name)) }
        .sortedBy { it.name }

private fun SupportSQLiteDatabase.indexColumns(index: String): List<String> =
    rows("PRAGMA index_info(`$index`)") { cursor -> cursor.int("seqno") to cursor.string("name") }
        .sortedBy { it.first }
        .map { it.second }

private fun SupportSQLiteDatabase.column(sql: String): List<String> =
    rows(sql) { cursor -> cursor.getString(0) }

private fun <T> SupportSQLiteDatabase.rows(sql: String, read: (Cursor) -> T): List<T> =
    query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(read(cursor))
        }
    }

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.stringOrNull(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
