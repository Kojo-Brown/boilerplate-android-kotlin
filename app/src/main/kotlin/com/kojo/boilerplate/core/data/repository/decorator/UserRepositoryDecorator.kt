package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.data.repository.UserRepository

/**
 * A [UserRepository] that adds one behaviour to another [UserRepository].
 *
 * The decorators in this package each add exactly one thing — retry, caching, telemetry — and
 * none of them knows what it is wrapping. That is what lets the stack be reordered, tested a
 * layer at a time, and cut down to nothing in a build that does not want any of it: the app
 * depends on `UserRepository`, and `RepositoryModule` decides how many of these sit behind it.
 *
 * ## Why this interface exists at all
 *
 * Only to expose [delegate]. Composition is otherwise invisible from outside — that is the
 * point of the pattern — and the one question worth asking of an assembled stack is *what
 * order is it in*, which is exactly what nothing else can answer. `UserRepositoryDecoratorTest`
 * walks this chain and asserts the order, so the decision recorded in `docs/decorator.md`
 * cannot be silently reversed by an edit to the module.
 *
 * ## Why there is no abstract base class
 *
 * A `DelegatingUserRepository` forwarding all six methods, with each decorator overriding the
 * two or three it changes, would delete about forty lines of one-line overrides across this
 * package. It would also mean that adding a method to [UserRepository] compiles, and every
 * decorator silently forwards it: an unretried, uncached, unmeasured operation, with no signal
 * anywhere. Implementing the interface directly in each decorator makes that a compile error in
 * three files, and each one is a decision someone has to make on purpose. The repetition is the
 * feature.
 */
interface UserRepositoryDecorator : UserRepository {

    /** The repository this one wraps. */
    val delegate: UserRepository
}
