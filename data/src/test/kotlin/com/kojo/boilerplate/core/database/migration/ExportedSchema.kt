package com.kojo.boilerplate.core.database.migration

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One version of the database as Room's compiler exported it, read back off disk.
 *
 * ## Why the tests read these files rather than hand-written DDL
 *
 * A migration test has to start from the *old* schema, and there are only two ways to get one:
 * write the old `CREATE TABLE` out by hand, or read what the compiler recorded at the time. The
 * hand-written version is a second description of the same thing, maintained by whoever
 * remembers to — and when it drifts it does not fail, it passes, because a test that migrates
 * from the wrong starting point still reaches a schema Room accepts. Reading the exported file
 * is what makes `data/schemas/` load-bearing: delete `1.json` and [AppDatabaseMigrationTest]
 * stops compiling a story and starts failing.
 *
 * This is the same construction `androidx.room.testing.MigrationTestHelper` performs. It is
 * reproduced here rather than depended on because `MigrationTestHelper` loads its bundles from
 * an *instrumentation* context's assets, which is a source set that never runs in this
 * repository — see the comment on the Robolectric dependency in `data/build.gradle.kts`.
 *
 * Only the parts of the bundle these tests need are modelled. `fields`, `indices` and
 * `foreignKeys` are deliberately not: the SQL Room recorded is the authority on what the table
 * was, and re-deriving it from the field list here would be a third description competing with
 * the other two.
 *
 * @property version the schema version this file describes.
 * @property identityHash Room's fingerprint of the schema, written into `room_master_table` by
 *   [setupQueries]. Nothing here compares it — Room does, when the migrated database is opened.
 * @property createQueries every statement needed to build the schema itself: one `CREATE TABLE`
 *   per entity, then each entity's indices, then each view.
 * @property setupQueries Room's own bookkeeping — creating `room_master_table` and recording
 *   [identityHash] in it.
 */
data class ExportedSchema(
    val version: Int,
    val identityHash: String,
    val createQueries: List<String>,
    val setupQueries: List<String>,
) {
    /** Everything needed to turn an empty file into a database at [version]. */
    val allQueries: List<String> get() = createQueries + setupQueries

    companion object {

        /**
         * The tokens Room writes into an exported `createSql` where the name belongs, so that
         * the same statement can be reused against a temporary table during an auto-migration.
         * Substituting them is the caller's job, and Room's own test helper does exactly this.
         *
         * The substitution is the bare name, with no quoting added: the exported SQL already
         * carries the backticks around the placeholder.
         */
        private const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"

        private const val VIEW_NAME_PLACEHOLDER = "\${VIEW_NAME}"

        private val json = Json { ignoreUnknownKeys = true }

        /** Reads `<directory>/<version>.json`. */
        fun read(directory: File, version: Int): ExportedSchema {
            val file = File(directory, "$version.json")
            require(file.isFile) {
                "No exported schema at ${file.absolutePath}. Room writes one per version into " +
                    "the directory named by `room { schemaDirectory(…) }` in data/build.gradle.kts; " +
                    "if it is missing, it was generated and never committed."
            }
            return parse(file.readText())
        }

        /** Every version exported into [directory], ascending. */
        fun versionsIn(directory: File): List<Int> =
            directory.listFiles { file -> file.extension == "json" }
                .orEmpty()
                .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
                .sorted()

        private fun parse(text: String): ExportedSchema {
            val database = json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
            val entities = database.getValue("entities").jsonArray.map { it.jsonObject }
            val views = database["views"]?.jsonArray.orEmpty().map { it.jsonObject }

            val createQueries = entities.flatMap { entity ->
                val tableName = entity.getValue("tableName").jsonPrimitive.content
                val statements = listOf(entity) + entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject }
                statements.map { it.getValue("createSql").jsonPrimitive.content }
                    .map { it.replace(TABLE_NAME_PLACEHOLDER, tableName) }
            } + views.map { view ->
                view.getValue("createSql").jsonPrimitive.content
                    .replace(VIEW_NAME_PLACEHOLDER, view.getValue("viewName").jsonPrimitive.content)
            }

            return ExportedSchema(
                version = database.getValue("version").jsonPrimitive.int,
                identityHash = database.getValue("identityHash").jsonPrimitive.content,
                createQueries = createQueries,
                setupQueries = database["setupQueries"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull },
            )
        }
    }
}
