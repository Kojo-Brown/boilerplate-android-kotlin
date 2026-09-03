package com.kojo.boilerplate.core.domain.sync

/**
 * Mints the key that identifies one mutation to the server.
 *
 * ## What the key is for
 *
 * A request that changes something on the server has exactly one safe answer to "did that
 * happen?", and it is not one the client can work out on its own. A `PATCH` that times out, or
 * whose response is lost, or whose process is killed between the write and the acknowledgement,
 * is indistinguishable from one the server never received — so the client must retry, and a
 * server with no way to recognise the repeat applies the change twice. For a display-name edit
 * that is invisible; for anything with an effect that accumulates it is the bug that costs
 * money.
 *
 * A client-generated key is what makes the repeat recognisable. The client decides, *before the
 * first attempt*, what this mutation is called; every attempt carries the same name; and the
 * server stores the outcome under it and replays that outcome rather than re-applying the
 * change. `docs/idempotency.md` describes both halves.
 *
 * ## Why the client generates it and not the server
 *
 * Because a server-issued key needs a round trip to obtain, and a round trip that can fail is
 * exactly what the key exists to make safe. The client would need an idempotency key for the
 * request that fetches its idempotency key.
 *
 * ## Why this is an interface
 *
 * Not to allow a second implementation — `UuidIdempotencyKeyGenerator` in `:data` is the only
 * one and no other is wanted. It is here so a test can state which key it expects to travel,
 * which is the whole assertion the push path exists to support —
 * [com.kojo.boilerplate.core.domain.repository.PendingUserChangeRepository] states it:
 * *the second attempt sends the key the first one did*. Against `UUID.randomUUID()` that is not
 * a property a test can express — every key is different and equally plausible.
 */
fun interface IdempotencyKeyGenerator {

    /**
     * A key for one new mutation.
     *
     * Must be unique across every mutation this installation will ever make, including across
     * reinstalls and across devices sharing an account: two mutations that collide on a key are
     * one mutation as far as the server is concerned, and the second one is silently dropped.
     * It does not have to be unguessable — it names a change the caller is authorised to make
     * anyway — but a random UUID satisfies both properties at once and is what `:data` binds.
     */
    fun newKey(): String
}
