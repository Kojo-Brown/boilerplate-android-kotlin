package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.telemetry.RepositoryTelemetry
import kotlinx.coroutines.CoroutineScope

/**
 * Wraps [base] in the decorator stack the app runs with, outermost last.
 *
 * The order is the only real decision in this package, and it is a function rather than three
 * nested constructor calls inside a `@Provides` so that it can be called by something other
 * than Dagger — `UserRepositoryDecoratorTest` asserts the assembled chain, which is the only
 * check that a reordering was meant. A Hilt graph cannot be inspected from a JVM unit test, and
 * an order that is only expressed inside the DI module is an order nothing verifies.
 *
 * The stack, innermost first:
 *
 * 1. [RetryingUserRepository] — closest to the transport, because a retry is a property of the
 *    request. Everything above it sees one logical operation rather than the attempts it took.
 * 2. [CachingUserRepository] — above retry, so a fresh result costs neither a request nor a
 *    retry schedule. Below it would mean the cache never saw a call that the retry loop
 *    repeated.
 * 3. [TelemetryUserRepository] — outermost, so its durations and outcomes are the ones the
 *    caller experienced: retries included, cache hits as near-zero successes.
 *
 * `docs/decorator.md` works through what each swap would change, including the one this
 * deliberately gives up — from outside the cache, telemetry cannot report a hit rate.
 *
 * @param scope the process-lifetime scope the cache hosts shared in-flight requests in. It must
 *   outlive any caller, which is what makes joining an in-flight request safe; see
 *   [CachingUserRepository].
 */
fun decorateUserRepository(
    base: UserRepository,
    telemetry: RepositoryTelemetry,
    scope: CoroutineScope,
): UserRepository = TelemetryUserRepository(
    delegate = CachingUserRepository(
        delegate = RetryingUserRepository(delegate = base),
        scope = scope,
    ),
    telemetry = telemetry,
)
