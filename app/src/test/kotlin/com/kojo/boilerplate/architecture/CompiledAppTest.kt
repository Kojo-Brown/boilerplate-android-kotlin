package com.kojo.boilerplate.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Guards the discovery every other contract test in this package is built on.
 *
 * All of them ask questions about the *assembled* app — every repository implementation, every
 * `ViewModel`, every Compose input — and all of them answer from [CompiledApp]. If a module's
 * classes are not on this classpath, those audits do not fail: they quietly report on a smaller
 * app and pass. That is the failure mode this test exists to make loud, and it is a live risk
 * rather than a hypothetical one, because `:app` reaching every module is a property of
 * `app/build.gradle.kts` that nothing else checks.
 */
class CompiledAppTest {

    @Test
    fun `discovery sees every module`() {
        assertEquals(
            emptyList<String>(),
            CompiledApp.missingModulePackages(),
            "No compiled class was found for these packages, so every contract test in this " +
                "package is auditing an app that is missing them. Either `:app` no longer " +
                "depends on the module that declares them — add the dependency, or drop the " +
                "audit deliberately — or the package moved and EXPECTED_MODULE_PACKAGES in " +
                "CompiledApp should move with it.",
        )
    }
}
