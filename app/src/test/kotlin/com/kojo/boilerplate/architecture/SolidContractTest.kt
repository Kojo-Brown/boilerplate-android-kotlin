package com.kojo.boilerplate.architecture

import java.lang.reflect.Method
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Holds the repository layer to the shape `docs/solid.md` says it has.
 *
 * ### Why an audit needs a test at all
 *
 * A written audit is accurate on the day it is written and silently wrong afterwards. The
 * findings on that page are not bugs with failing tests attached — they are statements about
 * structure: how many implementations an abstraction has, which methods it declares, which
 * framework types reach it. Nothing in a compiler or a linter objects when one of those
 * changes, so the page would drift from the code with no signal anywhere, and the next reader
 * would be reasoning from a description of an app that no longer exists.
 *
 * ### Why fixing a finding fails this too
 *
 * Every assertion here is an equality against a recorded set, not a subset check or an
 * upper bound. A new violation fails, which is the obvious half; repairing a recorded one
 * fails as well. That is deliberate. A fix that leaves `docs/solid.md` still describing the
 * problem is how an audit turns into folklore — the page keeps warning about a leak that was
 * sealed months ago, so readers learn to discount it, and the findings that are still real
 * get discounted with them. Failing on the repair forces the page to be edited in the same
 * change, and the failure message says so.
 *
 * ### What it reads
 *
 * Every class the app compiles, across every module, via [CompiledApp] — for the reason given
 * there: a hand-written list of types omits the one added next month, and an anchor-based walk
 * would only ever see the module that declares the anchor. Types are matched by role
 * suffix (`…Repository`, `…RepositoryImpl`, `…UseCase`), which is what `docs/solid.md`
 * records as this check's blind spot: a data abstraction named anything else is invisible
 * to it.
 */
class SolidContractTest {

    @Test
    fun `the audited surface is exactly the one the audit describes`() {
        assertEquals(
            AUDITED_REPOSITORIES,
            typesInRole(REPOSITORY_SUFFIX).map { it.name }.sorted(),
            "The set of repository types changed. docs/solid.md audits the list on the left; " +
                "audit the new one and update both.",
        )
    }

    /**
     * Guards the interface-segregation finding, which counts callers per method and so is
     * only as current as the method list it was counted over. A seventh method on
     * `UserRepository` — or one of the three unused ones being deleted — changes that
     * finding's arithmetic.
     */
    @Test
    fun `each repository abstraction declares exactly the methods the audit measured`() {
        val declared = abstractions().associate { abstraction ->
            abstraction.name to abstraction.sourceMethods().map { it.declaredName() }.sorted()
        }
        assertEquals(
            AUDITED_ABSTRACTION_METHODS,
            declared,
            "A repository abstraction gained or lost a method. The interface-segregation " +
                "section of docs/solid.md counts callers per method; recount and update it.",
        )
    }

    /**
     * The audit's headline finding, now in its repaired form.
     *
     * This assertion used to be `emptyList()` — "no use-case layer exists" — with a note that
     * the next Phase 8 item would introduce the layer and was expected to fail it. That item
     * landed, so this is the edit it forced, which is exactly what failing on a repair is for.
     *
     * What is checked is app-wide rather than scoped to `core.domain`, and that is the point
     * of keeping it here alongside `DomainLayerContractTest`. The `ForbiddenImport` rule in
     * `config/detekt/detekt.yml` is scoped by path, so a use case declared anywhere else —
     * `feature/home/RefreshUseCase.kt` being the obvious way it would happen — is outside the
     * rule's reach and would carry no framework guarantee at all. This is what notices.
     */
    @Test
    fun `every use case lives in the domain layer, and is one the audit describes`() {
        assertEquals(
            AUDITED_USE_CASES,
            typesInRole(USE_CASE_SUFFIX).map { it.name }.sorted(),
            "The use-case roster changed. A use case outside $DOMAIN_PACKAGE is not covered " +
                "by the ForbiddenImport rule in config/detekt/detekt.yml and can import the " +
                "framework freely — move it there. If the roster itself changed, docs/solid.md " +
                "finding 1 and docs/clean-architecture.md both describe the list on the left.",
        )
    }

    /**
     * Dependency inversion, in the form it can be checked mechanically. Two implementations
     * behind one abstraction is not a violation in itself, but it is the point at which
     * "which one is bound?" stops having an obvious answer and the audit needs redoing.
     */
    @Test
    fun `every repository abstraction has exactly the implementations the audit found`() {
        val implementations = abstractions().associate { abstraction ->
            abstraction.name to implementationsOf(abstraction).map { it.name }.sorted()
        }
        assertEquals(
            AUDITED_IMPLEMENTATIONS,
            implementations,
            "The implementations behind a repository abstraction changed. Re-read the " +
                "dependency-inversion section of docs/solid.md.",
        )
    }

    /** Finding 3: a repository nothing can be substituted for, injected by its concrete type. */
    @Test
    fun `repositories with no abstraction are only the ones the audit records`() {
        val declaredAbstractions = abstractions()
        val orphans = typesInRole(REPOSITORY_SUFFIX)
            .filterNot { it.isInterface }
            .filter { implementation ->
                declaredAbstractions.none { it.isAssignableFrom(implementation) }
            }
        assertEquals(
            REPOSITORIES_WITHOUT_ABSTRACTION,
            orphans.map { it.name }.sorted(),
            "A repository gained or lost its abstraction. Finding 3 in docs/solid.md is " +
                "about exactly this list.",
        )
    }

    /**
     * Finding 2: an abstraction parameterised by an Android type is as framework-bound as the
     * implementation it was extracted from, and the binding travels up to every caller.
     *
     * Signatures only. A framework type used *inside* an implementation is what an
     * implementation is for; one that appears on the interface is the leak.
     */
    @Test
    fun `framework types reach an abstraction only where the audit records`() {
        val leaks = abstractions()
            .flatMap { abstraction ->
                abstraction.sourceMethods().flatMap { frameworkTypesIn(abstraction, it) }
            }
            .sorted()
        assertEquals(
            FRAMEWORK_TYPES_ON_ABSTRACTIONS,
            leaks,
            "The Android types on repository abstractions changed. Finding 2 in " +
                "docs/solid.md records the ones on the left.",
        )
    }

    // Inspection

    private fun abstractions(): List<Class<*>> = typesInRole(REPOSITORY_SUFFIX).filter { it.isInterface }

    private fun implementationsOf(abstraction: Class<*>): List<Class<*>> = CompiledApp.classes()
        .filterNot { it.isInterface }
        .filter { it.isDeclaredInSource() && abstraction.isAssignableFrom(it) }

    private fun frameworkTypesIn(owner: Class<*>, method: Method): List<String> =
        (method.parameterTypes.toList() + method.returnType)
            .filter { it.isFrameworkType() }
            .distinct()
            .map { "${owner.name}.${method.declaredName()} takes or returns ${it.name}" }

    /**
     * Bridge and synthetic methods are the compiler's, not the interface's. None are generated
     * for these declarations today; filtering them keeps a later generic signature or a
     * `@JvmDefault` from quietly enlarging the measured surface.
     */
    private fun Class<*>.sourceMethods(): List<Method> =
        declaredMethods.filterNot { it.isBridge || it.isSynthetic }

    /**
     * The name the function was declared with, which is not always the name on the JVM method.
     * Kotlin mangles a function that returns an inline class by appending `-` and a hash of its
     * signature, so `UserRepository.syncUser` — which returns `Result<User>` — compiles to
     * `syncUser-gIAlu-s`. The hash moves whenever the signature does, and the audit is about
     * the declared function rather than the encoding, so the suffix comes off. `-` cannot
     * appear in a Kotlin identifier that is not backticked, so nothing else is at risk here.
     */
    private fun Method.declaredName(): String = name.substringBefore('-')

    private fun Class<*>.isFrameworkType(): Boolean =
        name.startsWith("android.") || name.startsWith("androidx.")

    // Discovery

    private fun typesInRole(suffix: String): List<Class<*>> = CompiledApp.classes()
        .filter { it.isDeclaredInSource() && it.simpleName.removeSuffix(IMPL_SUFFIX).endsWith(suffix) }

    /**
     * Excludes everything KSP and Hilt put in these packages alongside the source. Every
     * generated name carries a `_` (`UserRepositoryImpl_Factory`, `Hilt_MainActivity`) or a
     * `$` (nested and companion classes, lambdas); no hand-written declaration in this app
     * carries either.
     */
    private fun Class<*>.isDeclaredInSource(): Boolean =
        !isSynthetic && !name.contains('$') && !simpleName.contains('_')

    private companion object {
        const val DOMAIN_PACKAGE = "com.kojo.boilerplate.core.domain"
        const val REPOSITORY_SUFFIX = "Repository"
        const val USE_CASE_SUFFIX = "UseCase"
        const val IMPL_SUFFIX = "Impl"

        /**
         * Finding 1's fix, asserted whole and by fully-qualified name — so a use case that
         * moves out of the domain package fails this even though its simple name is unchanged.
         */
        val AUDITED_USE_CASES = listOf(
            "$DOMAIN_PACKAGE.usecase.ObserveUserProfileUseCase",
            "$DOMAIN_PACKAGE.usecase.PerformBackgroundSyncUseCase",
            "$DOMAIN_PACKAGE.usecase.RefreshVisibleUsersUseCase",
        )

        /**
         * Alphabetical, and asserted whole: a repository that disappears changes the audit too.
         *
         * The three decorators are repositories by this check's own definition — they carry the
         * role suffix and implement the interface — and being counted is correct rather than an
         * accident of naming. Each one is a `UserRepository` that any caller could be given, so
         * the dependency-inversion and interface-segregation questions this file asks apply to
         * them exactly as they apply to `UserRepositoryImpl`.
         */
        val AUDITED_REPOSITORIES = listOf(
            "com.kojo.boilerplate.core.auth.GoogleAuthRepository",
            "com.kojo.boilerplate.core.auth.GoogleAuthRepositoryImpl",
            "com.kojo.boilerplate.core.data.repository.UserRepositoryImpl",
            "com.kojo.boilerplate.core.data.repository.decorator.CachingUserRepository",
            "com.kojo.boilerplate.core.data.repository.decorator.RetryingUserRepository",
            "com.kojo.boilerplate.core.data.repository.decorator.TelemetryUserRepository",
            "com.kojo.boilerplate.core.datastore.ThemePreferencesRepository",
            "com.kojo.boilerplate.core.domain.repository.UserRepository",
        )

        val AUDITED_ABSTRACTION_METHODS = mapOf(
            "com.kojo.boilerplate.core.auth.GoogleAuthRepository" to listOf("signIn", "signOut"),
            "com.kojo.boilerplate.core.domain.repository.UserRepository" to listOf(
                "getUser",
                "getUsers",
                "saveUser",
                "syncCurrentUser",
                "syncUser",
                "syncUsers",
            ),
        )

        /**
         * `UserRepository` now has four implementations and only one of them talks to anything:
         * the other three are the decorators, each of which is an implementation whose entire
         * behaviour is "another `UserRepository`, plus one thing". That is the pattern working
         * rather than the dependency-inversion finding this assertion guards — what the finding
         * is about is *two implementations a caller might be given by mistake*, and here only
         * the assembled stack is bound. `UserRepositoryDecoratorTest` pins the assembly itself.
         */
        val AUDITED_IMPLEMENTATIONS = mapOf(
            "com.kojo.boilerplate.core.auth.GoogleAuthRepository" to
                listOf("com.kojo.boilerplate.core.auth.GoogleAuthRepositoryImpl"),
            "com.kojo.boilerplate.core.domain.repository.UserRepository" to listOf(
                "com.kojo.boilerplate.core.data.repository.UserRepositoryImpl",
                "com.kojo.boilerplate.core.data.repository.decorator.CachingUserRepository",
                "com.kojo.boilerplate.core.data.repository.decorator.RetryingUserRepository",
                "com.kojo.boilerplate.core.data.repository.decorator.TelemetryUserRepository",
            ),
        )

        /** Finding 3. `MainActivity` injects this by its concrete type. */
        val REPOSITORIES_WITHOUT_ABSTRACTION = listOf(
            "com.kojo.boilerplate.core.datastore.ThemePreferencesRepository",
        )

        /**
         * Finding 2. Credential Manager genuinely needs an `Activity` context, so the
         * parameter is real; carrying it on the abstraction is what the finding is about.
         */
        val FRAMEWORK_TYPES_ON_ABSTRACTIONS = listOf(
            "com.kojo.boilerplate.core.auth.GoogleAuthRepository.signIn takes or returns " +
                "android.content.Context",
        )
    }
}
