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
 * ### Why the classloader, and not an anchor class
 *
 * Each of these tests used to carry its own copy of this walk, anchored on some main-source
 * type: `File(UserRepository::class.java.protectionDomain.codeSource.location.toURI())` and
 * then a recursive walk of that directory. That worked while the app was one module, because
 * there was exactly one output directory and every class was in it. After the split an anchor
 * finds only the module that declares it — `UserRepository` would reach `:core:domain` and
 * nothing else — and the audit would quietly shrink to whatever the anchor happened to be near.
 * A test that checks less than it claims to is worse than no test.
 *
 * So discovery asks the classloader instead: `getResources("com/kojo/boilerplate")` returns one
 * URL per classpath entry that carries the package, whether the entry is a directory or a jar.
 * Deliberately *not* `System.getProperty("java.class.path")`, which is only the truth when the
 * JVM was started with `-cp`: Gradle's test worker and the JUnit console launcher both put the
 * application classpath in a classloader of their own and leave the system property naming
 * their own bootstrap jar. Under the offline harness that property reports one jar and the walk
 * finds nothing at all.
 *
 * `:app` depends on every module, which is what makes this complete. That is asserted rather
 * than assumed — see [missingModulePackages] and `CompiledAppTest`.
 *
 * ### What is excluded
 *
 * Test output. This class's own code source is dropped, so the fakes and contract tests in
 * `:app/src/test` are not mistaken for application classes. Test code in *other* modules is
 * never on this classpath to begin with: a module's `testImplementation` dependencies are not
 * transitive, so `:core:testing`'s fakes reach the modules that ask for them and never reach
 * here.
 *
 * Classes are loaded with `initialize = false` so that reaching one cannot run its static
 * initialiser: several would try to touch the Android framework, which is only a stub under a
 * unit test.
 */
internal object CompiledApp {

    const val PACKAGE = "com.kojo.boilerplate"

    private const val PACKAGE_PATH = "com/kojo/boilerplate"

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
        "$PACKAGE.core.telemetry",
        "$PACKAGE.core.ui",
        "$PACKAGE.core.data",
        "$PACKAGE.core.database",
        "$PACKAGE.core.datastore",
        "$PACKAGE.core.di",
        "$PACKAGE.core.network",
        "$PACKAGE.feature.home",
        "$PACKAGE.feature.profile",
        "$PACKAGE.feature.scanner",
        "$PACKAGE.feature.signin",
        "$PACKAGE.feature.textrecognition",
        "$PACKAGE.navigation",
    )

    private val classes: List<Class<*>> by lazy { load() }

    fun classes(): List<Class<*>> = classes

    /** The package prefixes in [EXPECTED_MODULE_PACKAGES] that no compiled class matches. */
    fun missingModulePackages(): List<String> {
        val names = classes.map { it.name }
        return EXPECTED_MODULE_PACKAGES.filterNot { prefix ->
            names.any { it.startsWith("$prefix.") }
        }
    }

    private fun load(): List<Class<*>> {
        val loader = CompiledApp::class.java.classLoader
        val ownOutput = File(
            CompiledApp::class.java.protectionDomain.codeSource.location.toURI(),
        ).canonicalPath
        val names = loader.getResources(PACKAGE_PATH).toList()
            .flatMap { classNamesUnder(it, ownOutput) }
            .distinct()
            .sorted()
        check(names.isNotEmpty()) {
            "Found no $PACKAGE classes on the test runtime classpath. Discovery is broken; " +
                "every contract test in this package is reporting on an empty set."
        }
        return names.map { Class.forName(it, false, loader) }
    }

    /**
     * The app classes under one classpath entry, given the URL of the package root inside it.
     *
     * `entry` is either a directory — `file:…/classes/com/kojo/boilerplate/` — or a jar —
     * `jar:file:…/x.jar!/com/kojo/boilerplate/`. Anything under [ownOutput] is dropped, which is
     * how this test source set's own classes stay out of an audit of the application.
     */
    private fun classNamesUnder(entry: URL, ownOutput: String): List<String> = when (entry.protocol) {
        "file" -> {
            val directory = File(entry.toURI())
            if (directory.canonicalPath.startsWith(ownOutput)) {
                emptyList()
            } else {
                directory.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { "$PACKAGE." + it.relativeTo(directory).invariantSeparatorsPath.toBinaryName() }
                    .toList()
            }
        }

        "jar" -> {
            val archivePath = entry.path.substringAfter("file:").substringBefore("!")
            val archive = File(URL("file:$archivePath").toURI())
            if (archive.canonicalPath.startsWith(ownOutput)) {
                emptyList()
            } else {
                // A corrupt or non-zip entry should not take the whole audit down.
                runCatching {
                    JarFile(archive).use { jar ->
                        jar.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .map { it.name.toBinaryName() }
                            .filter { it.startsWith("$PACKAGE.") }
                            .toList()
                    }
                }.getOrDefault(emptyList())
            }
        }

        else -> emptyList()
    }

    private fun String.toBinaryName(): String = removeSuffix(".class").replace('/', '.')
}
