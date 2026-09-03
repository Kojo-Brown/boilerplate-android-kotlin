package com.kojo.boilerplate.core.data.sync

import com.kojo.boilerplate.core.domain.sync.IdempotencyKeyGenerator
import java.util.UUID
import javax.inject.Inject

/**
 * A random version-4 UUID per mutation.
 *
 * ## Why random and not derived
 *
 * A key built out of the change itself — a hash of the row id, the pending fields and their
 * values — is the tempting alternative, because it makes "same change, same key" true by
 * construction and needs no column to store it in. It also makes *edit, revert, edit back*
 * indistinguishable from a single change: the third mutation hashes to the first one's key, the
 * server recognises it as a duplicate, and the user's change is dropped with everything
 * reporting success. Randomness is what keeps two mutations that happen to look alike apart,
 * and the column on `users` is the price of it.
 *
 * A counter would be cheaper still and is worse than both: it repeats after a reinstall, and it
 * collides immediately across two devices signed into one account.
 *
 * ## Why `UUID.randomUUID`
 *
 * It draws from `SecureRandom`, so keys do not repeat after a process fork or a restore from
 * backup — the two ways a seeded generator quietly starts issuing names it has issued before.
 * The 122 random bits make a collision unreachable at any rate this app can produce mutations.
 *
 * It is not fast, in the sense that it blocks on the system entropy pool, and that is not worth
 * avoiding here: it is called once per local edit, from `saveUser`, which is already on the IO
 * dispatcher and already about to write to a database.
 */
class UuidIdempotencyKeyGenerator @Inject constructor() : IdempotencyKeyGenerator {

    override fun newKey(): String = UUID.randomUUID().toString()
}
