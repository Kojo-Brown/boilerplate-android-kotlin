package com.kojo.boilerplate.core.datastore

/**
 * A decrypted access/refresh pair, in memory.
 *
 * [toString] is overridden to redact both, and that is the only reason this file has any body
 * at all. A `data class` generates a `toString` that prints every property, so the default one
 * puts both credentials into any line that interpolates this object — a debug log, an
 * `error("unexpected state: $tokens")`, a crash reporter's captured locals, an assertion
 * message in a failing test that is then pasted into an issue. Every one of those is a plaintext
 * token written somewhere durable, which is exactly what encrypting the store exists to prevent,
 * and none of them would look wrong in review.
 *
 * The lengths are kept because they are the thing worth knowing when debugging — an empty or
 * truncated token is a real failure mode — and neither narrows the value.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
) {
    override fun toString(): String =
        "AuthTokens(accessToken=<redacted, ${accessToken.length} chars>, " +
            "refreshToken=<redacted, ${refreshToken.length} chars>)"
}
