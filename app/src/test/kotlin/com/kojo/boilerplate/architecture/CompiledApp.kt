package com.kojo.boilerplate.architecture

import java.io.File
import java.net.URL
import java.util.jar.JarFile

/**
 * Every class this app compiles, across every module, read out of the test runtime classpath
 * rather than from a hand-written list.
 *
 * A list omits the class added next month, which is exactly the case a structural test is worth
 * having for. The contract tests in this package all ask questions about the *assembled* app —
 * every repository implementation, every `ViewModel`, every Compose input — so they need the
 * whole graph and not one module's slice of it.
 *
 * ### Where the roots come from
 *
 * Each of these tests used to carry its own copy of this walk, anchored on some main-source
 * type: `File(UserRepository::class.java.protectionDomain.codeSource.location.toURI())` and
 * then a recursive walk of that directory. That worked while the app was one module, because
 * there was exactly one output directory and every class was in it. After the split an anchor
 * finds only the module that declares it — `UserRepository` would reach `:core:domain` and
 * nothing else — and the audit would quietly shrink to whatever the anchor happened to be near.
 * A test that checks less than it claims to is worse than no test.
 *
 * So the roots are collected from two places, and both are needed:
 *
 * 1. `getResources("com/kojo/boilerplate")`, which returns one URL per classpath entry carrying
 *    the package, directory or jar. This finds every module `:app` depends on. Deliberately
 *    *not* `System.getProperty("java.class.path")`, which is only the truth when the JVM was
 *    started with `-cp`: Gradle's test worker and the JUnit console launcher both put the
 *    application classpath in a classloader of their own and leave that property naming their
 *    own bootstrap jar.
 * 2. [APP_MODULE_ANCHOR], resolved by name. Under AGP the enumeration above returned every
 *    dependency's output and not `:app`'s own — `com.kojo.boilerplate.navigation` went missing
 *    from a run where all twelve other packages were present — so the module the tests live in
 *    is reached through a class inside it instead. By name rather than by import on purpose:
 *    this file then carries no dependency on Compose or Hilt and stays compilable by the offline
 *    harness, where the anchor is simply absent and the walk carries on without it.
 *
 * Roots are compared for equality, never by path prefix. `…/kotlin-classes/debugUnitTest`
 * *starts with* `…/kotlin-classes/debug`, so a prefix test is one output-directory-layout change
 * away from silently excluding the main classes it was meant to keep.
 *
 * ### What is excluded
 *
 * This test source set's own output, so the fakes and contract tests in `:app/src/test` are not
 * mistaken for application classes. Test code in *other* modules is never on this classpath to
 * begin with: a module's `testImplementation` dependencies are not transitive, so
 * `:core:testing`'s fakes reach the modules that ask for them and never reach here.
 *
 * Classes are loaded with `initialize = false` so that reaching one cannot run its static
 * initialiser: several would try to touch the Android framework, which is only a stub under a
 * unit test.
 */
internal object CompiledApp {

    const val PACKAGE = "com.kojo.boilerplate"

    private const val PACKAGE_PATH = "com/kojo/boilerplate"

    /**
     * A class in `:app`'s own `main` source set, named as a string so that this file compiles
     * without it. See the class KDoc for why the module the tests live in needs an anchor at all.
     */
    private const val APP_MODULE_ANCHOR = "com.kojo.boilerplate.MainActivity"

    /**
     * Every module whose classes must be visible here, by the package prefix that identifies
     * it. Asserted whole by [missingModulePackages]: a new module that `:app` forgets to depend
     * on is invisible to every audit in this package, and the failure should name it rather
     * than showing up as an audit that silently stopped covering a layer.
     *
     * `:core:testing` is deliberately absent. Its fakes are `main`-source classes and it is a
     * test-only dependency of other modules, so it never reaches `:app` — if it ever appeared
     * here, something would be shipping it.
     *
     * The list is hand-written and therefore driftable: `ui.theme` sat here for one CI run after
     * the theme moved into `:core:ui`. The offline harness cross-checks it against the packages
     * the modules actually declare, which is where a stale entry should be caught.
     */
    private val EXPECTED_MODULE_PACKAGES = listOf(
        "$PACKAGE.core.auth",
        "$PACKAGE.core.common",
        "$PACKAGE.core.coroutines",
        "$PACKAGE.core.domain",
        "$PACKAGE.core.event",
        "$PACKAGE.core.navigation",
        "$PACKAGE.core.paging",
        "$PACKAGE.core.telemetry",
        "$PACKAGE.core.ui",
        "$PACKAGE.core.data",
        "$PACKAGE.core.database",
        "$PACKAGE.core.datastore",
        "$PACKAGE.core.di",
        "$PACKAGE.core.network",
        "$PACKAGE.core.work",
        "$PACKAGE.feature.home",
        "$PACKAGE.feature.profile",
        "$PACKAGE.feature.scanner",
        "$PACKAGE.feature.signin",
        "$PACKAGE.feature.textrecognition",
        "$PACKAGE.navigation",
    )

    private val roots: List<File> by lazy { findRoots() }

    private val classes: List<Class<*>> by lazy { load() }

    fun classes(): List<Class<*>> = classes

    /** The classpath entries this walk read, so a failure message can be acted on. */
    fun scannedRoots(): List<String> = roots.map { it.path }

    /** The package prefixes in [EXPECTED_MODULE_PACKAGES] that no compiled class matches. */
    fun missingModulePackages(): List<String> {
        val names = classes.map { it.name }
        return EXPECTED_MODULE_PACKAGES.filterNot { prefix ->
            names.any { it.startsWith("$prefix.") }
        }
    }

    private fun findRoots(): List<File> {
        val loader = CompiledApp::class.java.classLoader
        val ownOutput = codeSourceOf(CompiledApp::class.java)
        val fromClassLoader = loader.getResources(PACKAGE_PATH).toList().mapNotNull(::rootOf)
        val fromAnchor = runCatching { Class.forName(APP_MODULE_ANCHOR, false, loader) }
            .getOrNull()
            ?.let(::codeSourceOf)
        return (fromClassLoader + listOfNotNull(fromAnchor))
            .filter { it != ownOutput }
            .distinct()
    }

    private fun codeSourceOf(type: Class<*>): File? =
        type.protectionDomain?.codeSource?.location?.let { File(it.toURI()).canonicalFile }

    /**
     * The classpath entry a package URL sits in: the directory above `com/kojo/boilerplate`, or
     * the jar the entry names.
     */
    private fun rootOf(entry: URL): File? = when (entry.protocol) {
        "file" -> File(entry.toURI())
            .canonicalPath
            .removeSuffix(File.separator + PACKAGE_PATH.replace('/', File.separatorChar))
            .let { File(it).canonicalFile }

        "jar" -> File(URL(entry.path.substringBefore("!")).toURI()).canonicalFile

        else -> null
    }

    private fun load(): List<Class<*>> {
        val loader = CompiledApp::class.java.classLoader
        val names = roots.flatMap(::classNamesIn).distinct().sorted()
        check(names.isNotEmpty()) {
            "Found no $PACKAGE classes on the test runtime classpath. Discovery is broken; " +
                "every contract test in this package is reporting on an empty set. Roots " +
                "scanned: ${scannedRoots()}"
        }
        return names.map { Class.forName(it, false, loader) }
    }

    private fun classNamesIn(root: File): List<String> = when {
        root.isDirectory -> {
            val packageDirectory = File(root, PACKAGE_PATH)
            if (!packageDirectory.isDirectory) {
                emptyList()
            } else {
                packageDirectory.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map {
                        "$PACKAGE." +
                            it.relativeTo(packageDirectory).invariantSeparatorsPath.toBinaryName()
                    }
                    .toList()
            }
        }

        // A corrupt or non-zip entry should not take the whole audit down.
        root.isFile -> runCatching {
            JarFile(root).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { it.name.toBinaryName() }
                    .filter { it.startsWith("$PACKAGE.") }
                    .toList()
            }
        }.getOrDefault(emptyList())

        else -> emptyList()
    }

    private fun String.toBinaryName(): String = removeSuffix(".class").replace('/', '.')
}
