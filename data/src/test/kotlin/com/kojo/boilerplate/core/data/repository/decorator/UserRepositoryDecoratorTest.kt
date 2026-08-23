package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.telemetry.RepositoryTelemetry
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kotlin mangles a function returning an inline class by appending `-` and a hash of its
 * signature, so `syncUser` — which returns `Result<User>` — is `syncUser-gIAlu-s` on the JVM.
 * The hash moves whenever the signature does, and what is compared here is the declared
 * function, so the suffix comes off.
 */
private fun Method.declaredName(): String = name.substringBefore('-')

/**
 * The two things about the stack that nothing else can check.
 *
 * A decorator is invisible from outside — every layer is a `UserRepository` and so is the whole
 * stack — which is the point of the pattern and also why its two structural properties have no
 * natural failure mode. Nothing breaks loudly when the order is changed, and nothing breaks at
 * all when a decorator quietly stops implementing a method. Both are asserted here.
 */
class UserRepositoryDecoratorTest {

    private val base = ScriptedUserRepository()

    /**
     * The order recorded in `docs/decorator.md`, read off the object graph the app actually
     * builds rather than off the source of `decorateUserRepository`.
     *
     * Each swap this would catch changes behaviour in a way a reader would have to reason hard
     * to notice: caching under retry means a retry loop that never consults the cache; telemetry
     * under caching means durations that exclude the layer users wait on and hide cache hits
     * entirely.
     */
    @Test
    fun `the app's repository is decorated in the documented order`() {
        val stack = decorateUserRepository(
            base = base,
            telemetry = RepositoryTelemetry { },
            scope = CoroutineScope(Job()),
        )

        val chain = generateSequence(stack) { (it as? UserRepositoryDecorator)?.delegate }

        assertEquals(
            listOf(
                TelemetryUserRepository::class.java,
                CachingUserRepository::class.java,
                RetryingUserRepository::class.java,
                ScriptedUserRepository::class.java,
            ),
            chain.map { it.javaClass }.toList(),
            "The decorator order changed. docs/decorator.md explains what each position buys; " +
                "if the change is deliberate, that page moves with it.",
        )
    }

    /**
     * `UserRepositoryDecorator` deliberately has no abstract base class that forwards the
     * interface, so that a method added to `UserRepository` fails to compile in every decorator
     * until someone decides what that layer should do with it. This is the same guarantee for a
     * decorator that *removes* an override — deleting `RetryingUserRepository.saveUser` compiles
     * the moment a base class exists to inherit it from, and nothing else would notice.
     */
    @Test
    fun `each decorator implements every repository method itself`() {
        val required = UserRepository::class.java.declaredMethods.map { it.declaredName() }.toSet()

        DECORATORS.forEach { decorator ->
            val declared = decorator.declaredMethods
                .filterNot { it.isBridge || it.isSynthetic }
                .map { it.declaredName() }
                .toSet()

            assertEquals(
                emptySet<String>(),
                required - declared,
                "${decorator.simpleName} inherits or forwards these rather than declaring them. " +
                    "Each decorator states its own answer for every operation, including " +
                    "'pass it straight through' — see UserRepositoryDecorator.",
            )
        }
    }

    private companion object {

        val DECORATORS = listOf(
            TelemetryUserRepository::class.java,
            CachingUserRepository::class.java,
            RetryingUserRepository::class.java,
        )
    }
}
