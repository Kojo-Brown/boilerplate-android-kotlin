package com.kojo.boilerplate.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three ways certificate pinning stops protecting anything while every other gate stays
 * green.
 *
 * None of them is a failure. Each produces an app that builds, lints, passes every unit test and
 * makes successful requests against the real server — which is exactly why they belong in a test
 * rather than in a review comment. Pinning has no positive signal: an app that pins correctly and
 * an app that pins nothing behave identically until the day somebody is between them and the
 * server, and on that day only one of them notices.
 *
 * ### A client that was never pinned
 *
 * `NetworkModule` builds two `OkHttpClient`s and they share no configuration. The second one —
 * the unauthenticated client behind `AuthApi` — is the one that carries the password on sign-in
 * and the refresh token on every rotation, so it is the more valuable of the two to intercept,
 * and it is the easier to miss: it is defined above the main client, it takes different
 * dependencies, and nothing about adding `.certificatePinner(…)` to one draws attention to the
 * other. A third client added later for downloads or images has the same shape.
 *
 * ### A pinner built somewhere else
 *
 * [com.kojo.boilerplate.core.network.pinning.PinningPolicy] is where "at least two pins", "an
 * expiry is mandatory" and "the host this build talks to must be covered" are enforced. A
 * `CertificatePinner.Builder()` written at a call site is a pin set that none of those apply to,
 * and the most likely one to be written by hand is a single pin for a single host — which is the
 * configuration that bricks the app on the next key rotation.
 *
 * ### Verification loosened to make something else work
 *
 * `sslSocketFactory` and `hostnameVerifier` are how a client is opened up to a debugging proxy or
 * to a staging server with a self-signed certificate. Both are one line, both are normally added
 * temporarily, and neither leaves any trace in the app's behaviour once the reason for it has
 * been forgotten. With this repository's default pin set empty, they are also the whole of what
 * stands between this app and any certificate a device's CA store has been persuaded to trust.
 *
 * ### Why this reads source
 *
 * For the reason [TokenStorageContractTest] does: "this builder was given that call" is not a
 * fact a class file records — the chain compiles to a sequence of `invokevirtual`s on a builder
 * whose receiver is a local, and the absent call is absent. Comments and string literals are
 * blanked first ([KotlinSource]), which this file needs as much as its neighbours do: its own
 * prose names every call it bans.
 */
class CertificatePinningContractTest {

    @Test
    fun `every OkHttp client in the app is given a certificate pinner`() {
        val unpinned = OkHttpClients.chains()
            .filter { !it.calls.contains(PINNER_CALL) }
            .map { "${it.file}:${it.line}" }

        assertTrue(unpinned.isEmpty()) {
            "These `OkHttpClient.Builder()` chains never call `$PINNER_CALL…)`, so every request " +
                "they make is unpinned. Nothing else in this repository notices: the client " +
                "works, the tests pass, and the traffic is readable by anything holding a " +
                "certificate the device's CA store accepts. Take the decision from " +
                "`PinningDecision` as the other two do rather than building a pinner here:\n" +
                unpinned.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `the clients this rule is about are still where it looks`() {
        // Discovery pin. Without it the rule above passes by finding nothing the day
        // `NetworkModule` is split or the clients move, which is the day it should assert
        // hardest. The count is a floor rather than an equality: a third client is fine, and
        // failing it is exactly what the rule above is for.
        val chains = OkHttpClients.chains()

        assertTrue(chains.size >= EXPECTED_CLIENTS) {
            "Expected at least $EXPECTED_CLIENTS `OkHttpClient.Builder()` chains in src/main and " +
                "found ${chains.size}. If the clients moved, this test still works; if they are " +
                "built some other way now, this rule reads the wrong thing and needs rewriting " +
                "rather than deleting."
        }
        assertTrue(chains.any { it.file.endsWith(NETWORK_MODULE) }) {
            "No `OkHttpClient.Builder()` in $NETWORK_MODULE. Found: " +
                chains.joinToString(", ") { it.file }.ifEmpty { "none anywhere" }
        }
    }

    @Test
    fun `the rule that finds an unpinned client can still find one`() {
        // The chain scan decides whether the rule above asserts anything, and it can go quiet in
        // both directions: a scan that stopped early would report a pinned client as unpinned,
        // and one that ran past `build()` would find a neighbouring statement's pinner and
        // report an unpinned client as pinned. Both are silent, so both are pinned here.
        //
        // [OkHttpClients] is shared with [IntegrityAttestationContractTest] now, so these four
        // fixtures hold up that rule as well as this one — which is an argument for keeping them
        // here rather than thinning them out.
        assertTrue(OkHttpClients.scan(PINNED_FIXTURE).single().contains(PINNER_CALL)) {
            "a pinned chain reads as pinned"
        }
        assertTrue(!OkHttpClients.scan(UNPINNED_FIXTURE).single().contains(PINNER_CALL)) {
            "an unpinned chain reads as unpinned"
        }
        assertTrue(!OkHttpClients.scan(NEIGHBOUR_FIXTURE).first().contains(PINNER_CALL)) {
            "a chain does not borrow the pinner of the statement after it"
        }
        assertTrue(OkHttpClients.scan(NESTED_FIXTURE).single().contains(PINNER_CALL)) {
            "a nested builder's own `build()` does not end the outer chain"
        }
    }

    @Test
    fun `the pin set is built in exactly one place`() {
        val sites = SourceTree.mainSources().flatMap { file ->
            val source = KotlinSource(file.readText())
            PINNER_BUILDER.findAll(source.code).map { file.repositoryPath() }.toList()
        }
        val found = sites.joinToString(", ").ifEmpty {
            "nowhere — which means this rule reads the wrong thing and every invariant it is " +
                "protecting is unguarded"
        }

        // An equality rather than a ban, so that the rule fails in both directions: a second
        // construction site, and the day the only one moves or is renamed away from underneath
        // it. The second half is the one that would otherwise go quiet.
        assertEquals(listOf(POLICY_FILE), sites) {
            "`CertificatePinner.Builder()` belongs to `PinningPolicy` and nowhere else. That is " +
                "the only place enforcing a backup pin, a mandatory expiry and the rule that " +
                "the host this build talks to is covered at all — a pinner assembled at a call " +
                "site has none of those, and the one somebody writes by hand is a single pin " +
                "for a single host, which is the configuration that bricks every installation " +
                "on the next key rotation. Found in: $found"
        }
    }

    @Test
    fun `nothing in the app loosens TLS verification`() {
        val loosened = SourceTree.mainSources()
            .flatMap { file ->
                val source = KotlinSource(file.readText())
                LOOSENED_TLS.findAll(source.code).map {
                    "${file.repositoryPath()}:${source.lineOf(it.range.first)} — ${it.value}"
                }
            }

        assertTrue(loosened.isEmpty()) {
            "`sslSocketFactory` and `hostnameVerifier` replace the checks that decide whether " +
                "the peer is the server at all. Both are one line, both are normally added to " +
                "get a proxy or a self-signed staging certificate working, and neither changes " +
                "anything a test or a user can see once the reason for it is forgotten. If a " +
                "debug build needs a proxy, turn the pin set off for it rather than turning " +
                "verification off for everything:\n" + loosened.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `the rule that finds loosened verification can still find some`() {
        assertTrue(LOOSENED_TLS.containsMatchIn(".sslSocketFactory(factory, trustManager)"))
        assertTrue(LOOSENED_TLS.containsMatchIn(".hostnameVerifier { _, _ -> true }"))
        assertTrue(!LOOSENED_TLS.containsMatchIn(".certificatePinner(decision.certificatePinner)"))
    }

    @Test
    fun `the pin set configuration is still wired to the build`() {
        // Four files in a line — a properties file, a build script, a BuildConfig field and a
        // parser — and the chain is only as good as its weakest link. Deleting any one of them
        // leaves the others compiling and the app unpinned, which the rules above cannot see:
        // an `OkHttpClient` handed `CertificatePinner.DEFAULT` satisfies every one of them.
        val buildScript = File(SourceTree.root, DATA_BUILD_SCRIPT).readText()

        assertTrue(File(SourceTree.root, PINS_FILE).isFile) {
            "$PINS_FILE is gone. It is where the pin set is configured; without it the build " +
                "has nothing to read and every build is unpinned."
        }
        assertTrue(buildScript.contains(File(PINS_FILE).name)) {
            "$DATA_BUILD_SCRIPT no longer reads $PINS_FILE, so the pin set reaches no BuildConfig " +
                "field and `PinningPolicy.parse` is handed two empty strings forever."
        }
        BUILD_CONFIG_FIELDS.forEach { field ->
            assertTrue(buildScript.contains(field)) {
                "$DATA_BUILD_SCRIPT no longer writes `$field`, which `NetworkModule` reads."
            }
        }
    }

    private companion object {
        const val NETWORK_MODULE = "data/src/main/kotlin/com/kojo/boilerplate/core/di/NetworkModule.kt"
        const val POLICY_FILE =
            "data/src/main/kotlin/com/kojo/boilerplate/core/network/pinning/PinningPolicy.kt"
        const val DATA_BUILD_SCRIPT = "data/build.gradle.kts"
        const val PINS_FILE = "gradle/certificate-pins.properties"

        /** The two clients `NetworkModule` builds today. A floor, not an equality. */
        const val EXPECTED_CLIENTS = 2

        const val PINNER_CALL = ".certificatePinner("

        val PINNER_BUILDER = Regex("""\bCertificatePinner\.Builder\s*\(""")

        /**
         * The two calls that replace TLS verification rather than adding to it.
         *
         * Matched as calls — name then `(` or `{` — so that the word appearing as a parameter
         * name or a type is not a finding. `certificatePinner` is deliberately not in this set
         * and the fixture above says so, because a pattern loose enough to match it would ban
         * the thing this whole item adds.
         */
        val LOOSENED_TLS = Regex("""\.(sslSocketFactory|hostnameVerifier)\s*[({]""")

        val BUILD_CONFIG_FIELDS = listOf("CERTIFICATE_PINS", "CERTIFICATE_PIN_EXPIRY")

        val PINNED_FIXTURE = """
            OkHttpClient.Builder()
                .certificatePinner(decision.certificatePinner)
                .addInterceptor(logging)
                .build()
        """.trimIndent()

        val UNPINNED_FIXTURE = """
            OkHttpClient.Builder()
                .addInterceptor(logging)
                .build()
        """.trimIndent()

        val NEIGHBOUR_FIXTURE = """
            val a = OkHttpClient.Builder()
                .addInterceptor(logging)
                .build()
            val b = OkHttpClient.Builder()
                .certificatePinner(decision.certificatePinner)
                .build()
        """.trimIndent()

        val NESTED_FIXTURE = """
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { it.proceed(Request.Builder().url(u).build()) })
                .certificatePinner(decision.certificatePinner)
                .build()
        """.trimIndent()
    }
}
