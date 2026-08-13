package com.kojo.boilerplate.core.architecture

import com.kojo.boilerplate.core.domain.usecase.ObserveUserProfileUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile

/**
 * Holds `core.domain` to the one rule that makes it a domain layer: no Android in it.
 *
 * ### Why this exists alongside the detekt rule
 *
 * `ForbiddenImport` in `config/detekt/detekt.yml` is the enforcement that runs on every
 * commit and names the offending line — it is the primary gate and the one a contributor
 * will actually hit. But it reads *import directives*, and an import is not the only way to
 * name a type. `android.os.Build.VERSION.SDK_INT` written out in full at the call site
 * compiles to the same dependency with no import to flag, and a type inherited through a
 * supertype or surfaced by a generic signature never appears in the import list at all.
 *
 * This reads the compiled classes instead, where every one of those has already collapsed
 * into the same place: the constant pool. Anything the class references by name — parameter
 * and return descriptors, supertypes, annotations, field types, the target of every call it
 * makes — is a UTF-8 entry in that table. Scanning it for `android/` and `androidx/` catches
 * the framework however it got in.
 *
 * The two checks are deliberately redundant and deliberately different: the linter is fast,
 * precise about *where*, and blind to non-imports; this is slower, coarser about location,
 * and blind to nothing. See `docs/clean-architecture.md`.
 *
 * ### Why the layer's contents are pinned too
 *
 * A rule scoped to a package that has become empty passes forever while enforcing nothing.
 * [the domain layer is not empty] and [the use-case layer is exactly what the docs describe]
 * are what stop this file from turning into a green light for a layer that quietly went away.
 */
class DomainLayerContractTest {

    @Test
    fun `no domain class references the Android framework`() {
        val leaks = domainClassFiles().flatMap { (className, bytes) ->
            constantPoolStrings(bytes)
                .filter { it.containsFrameworkReference() }
                .distinct()
                .map { "$className references $it" }
        }.sorted()

        assertEquals(
            emptyList<String>(),
            leaks,
            "The Android framework reached core.domain. A use case that needs a Context, a " +
                "Bundle or a LiveData is a second presentation layer filed under `domain` — " +
                "invert the dependency behind an interface this app owns instead. See " +
                "docs/clean-architecture.md.",
        )
    }

    /** A rule scoped to an empty package enforces nothing; this is what notices. */
    @Test
    fun `the domain layer is not empty`() {
        assertTrue(
            domainClassFiles().isNotEmpty(),
            "Found no compiled classes under $DOMAIN_PACKAGE. Either the layer was removed — " +
                "in which case the ForbiddenImport rule in config/detekt/detekt.yml and " +
                "docs/clean-architecture.md are now guarding nothing and should go with it — " +
                "or this test is no longer finding the compiled output.",
        )
    }

    /**
     * The use-case roster, asserted whole.
     *
     * `docs/solid.md` finding 1 is that the profile-loading policy was duplicated across two
     * `ViewModel`s, and its fix is that exactly these two use cases own it. A third use case
     * appearing, or one of these disappearing back into a `ViewModel`, changes what that
     * finding says and should not happen quietly.
     */
    @Test
    fun `the use-case layer is exactly what the docs describe`() {
        val useCases = domainClassFiles().keys
            .filter { it.substringAfterLast('.').endsWith(USE_CASE_SUFFIX) }
            .sorted()

        assertEquals(
            DOCUMENTED_USE_CASES,
            useCases,
            "The set of use cases changed. docs/solid.md finding 1 and " +
                "docs/clean-architecture.md both describe the list on the left; update them.",
        )
    }

    // Detection

    /**
     * Both prefixes are needed and neither implies the other: `androidx/lifecycle/ViewModel`
     * does not contain the substring `android/`. Internal (slash-separated) form only, which
     * is how every class reference in a descriptor, a signature and the `Class` entries is
     * encoded — the dotted form appears only inside string literals, where a domain class
     * mentioning "android" in prose is not a dependency on it.
     */
    private fun String.containsFrameworkReference(): Boolean =
        contains("android/") || contains("androidx/")

    // Class-file reading

    /**
     * Every UTF-8 constant in [bytes], which between them name every type the class refers
     * to. The pool is walked rather than indexed: entry sizes are fixed per tag, and `long`
     * and `double` famously take two slots each — miss that and the walk desynchronises and
     * reads garbage from then on, which would make this test pass for the wrong reason.
     */
    private fun constantPoolStrings(bytes: ByteArray): List<String> {
        DataInputStream(bytes.inputStream()).use { input ->
            val magic = input.readInt()
            check(magic == CLASS_FILE_MAGIC) { "Not a class file: magic was ${magic.toUInt()}" }
            input.readUnsignedShort() // minor version
            input.readUnsignedShort() // major version

            val strings = mutableListOf<String>()
            val entryCount = input.readUnsignedShort()
            var index = 1
            while (index < entryCount) {
                when (val tag = input.readUnsignedByte()) {
                    TAG_UTF8 -> strings += input.readUTF()
                    TAG_INTEGER, TAG_FLOAT -> input.skipFully(FOUR_BYTES)
                    TAG_LONG, TAG_DOUBLE -> {
                        input.skipFully(EIGHT_BYTES)
                        // A long or double occupies this slot and the next one. The JVM spec
                        // calls this "a poor choice"; it is still the format.
                        index++
                    }

                    TAG_CLASS, TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE ->
                        input.skipFully(TWO_BYTES)

                    TAG_METHOD_HANDLE -> input.skipFully(THREE_BYTES)

                    TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE,
                    TAG_DYNAMIC, TAG_INVOKE_DYNAMIC,
                    -> input.skipFully(FOUR_BYTES)

                    else -> error("Unknown constant pool tag $tag at index $index")
                }
                index++
            }
            return strings
        }
    }

    /**
     * `InputStream.skip` may legally skip fewer bytes than asked. Everything downstream of a
     * short skip is misaligned, so the loop is not optional.
     */
    private fun DataInputStream.skipFully(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining.toLong()).toInt()
            check(skipped > 0) { "Unexpected end of class file with $remaining bytes to skip" }
            remaining -= skipped
        }
    }

    // Discovery

    /**
     * Binary name to raw bytes for every compiled class under [DOMAIN_PACKAGE], read from the
     * same output directory this module's own classes were loaded from — the discovery
     * `SolidContractTest` uses, for the same reason: a hand-written list omits the class
     * added next month.
     *
     * Nested and synthetic classes are kept, unlike in `SolidContractTest`. There the question
     * is which types the *author* declared; here it is what the compiler actually emitted, and
     * a lambda that captures a `Context` is exactly the leak this is looking for.
     */
    private fun domainClassFiles(): Map<String, ByteArray> {
        val root = File(
            ObserveUserProfileUseCase::class.java.protectionDomain.codeSource.location.toURI(),
        )
        val prefix = "$DOMAIN_PACKAGE."
        return if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(root).invariantSeparatorsPath.toBinaryName() to it.readBytes() }
                .filter { (name, _) -> name.startsWith(prefix) }
                .toMap()
        } else {
            JarFile(root).use { archive ->
                archive.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { it.name.toBinaryName() to archive.getInputStream(it).readBytes() }
                    .filter { (name, _) -> name.startsWith(prefix) }
                    .toMap()
            }
        }
    }

    private fun String.toBinaryName(): String = removeSuffix(".class").replace('/', '.')

    private companion object {
        const val DOMAIN_PACKAGE = "com.kojo.boilerplate.core.domain"
        const val USE_CASE_SUFFIX = "UseCase"

        val DOCUMENTED_USE_CASES = listOf(
            "com.kojo.boilerplate.core.domain.usecase.ObserveUserProfileUseCase",
            "com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase",
        )

        val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()

        const val TAG_UTF8 = 1
        const val TAG_INTEGER = 3
        const val TAG_FLOAT = 4
        const val TAG_LONG = 5
        const val TAG_DOUBLE = 6
        const val TAG_CLASS = 7
        const val TAG_STRING = 8
        const val TAG_FIELDREF = 9
        const val TAG_METHODREF = 10
        const val TAG_INTERFACE_METHODREF = 11
        const val TAG_NAME_AND_TYPE = 12
        const val TAG_METHOD_HANDLE = 15
        const val TAG_METHOD_TYPE = 16
        const val TAG_DYNAMIC = 17
        const val TAG_INVOKE_DYNAMIC = 18
        const val TAG_MODULE = 19
        const val TAG_PACKAGE = 20

        const val TWO_BYTES = 2
        const val THREE_BYTES = 3
        const val FOUR_BYTES = 4
        const val EIGHT_BYTES = 8
    }
}
