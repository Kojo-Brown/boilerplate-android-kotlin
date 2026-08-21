package com.kojo.boilerplate.core.architecture

import com.kojo.boilerplate.core.ui.udf.UdfViewModel
import java.io.File
import java.util.jar.JarFile

/**
 * Every class the Kotlin compiler produced for `src/main`, read out of the output directory
 * rather than from a hand-written list.
 *
 * A list omits the class added next month, which is exactly the case a structural test is
 * worth having for. Anchored on a main-source class and not on a test, whose classes sit in a
 * different directory.
 *
 * Loaded with `initialize = false` so that reaching a class cannot run its static initialiser:
 * several would try to touch the Android framework, which is only a stub under a unit test.
 *
 * `StabilityContractTest`, `SolidContractTest` and `DomainLayerContractTest` each predate this
 * and carry their own copy of the same walk. Consolidating them onto this is worth doing and
 * is deliberately not part of the change that introduced it — two of the three cannot be run
 * outside CI from the scheduled agent's environment, so the move should be made by someone who
 * can watch them go green.
 */
internal object CompiledApp {

    const val PACKAGE = "com.kojo.boilerplate"

    fun classes(): List<Class<*>> {
        val anchor = UdfViewModel::class.java
        val root = File(anchor.protectionDomain.codeSource.location.toURI())
        val loader = anchor.classLoader
        val names = if (root.isDirectory) classNamesUnder(root) else classNamesInJar(root)
        val appNames = names.filter { it.startsWith(PACKAGE) }
        check(appNames.isNotEmpty()) { "Found no $PACKAGE classes under $root" }
        return appNames.map { Class.forName(it, false, loader) }
    }

    private fun classNamesUnder(root: File): List<String> = root.walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .map { it.relativeTo(root).invariantSeparatorsPath.toBinaryName() }
        .toList()

    /**
     * The same walk for the day AGP hands unit tests a packaged classpath instead of the class
     * directories it uses today. Cheap to support, and the alternative is a test that fails for
     * a reason that has nothing to do with what it is checking.
     */
    private fun classNamesInJar(jar: File): List<String> = JarFile(jar).use { archive ->
        archive.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }
            .map { it.name.toBinaryName() }
            .toList()
    }

    private fun String.toBinaryName(): String = removeSuffix(".class").replace('/', '.')
}
