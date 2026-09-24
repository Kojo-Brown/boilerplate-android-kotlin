package com.kojo.boilerplate.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The four ways device attestation stops meaning anything while every other gate stays green.
 *
 * None of them is a failure. Each produces an app that builds, lints, passes every test and
 * signs in successfully against a real server — which is why they belong here rather than in a
 * review comment. Attestation shares that property with certificate pinning: an app that attests
 * correctly and an app that attests nothing behave identically until somebody is running a
 * patched copy of it, and on that day only one of them is telling the backend so.
 *
 * ### A verdict read in the app
 *
 * This is the big one, and it is the whole thesis of `docs/root-detection.md`. A Play Integrity
 * token is encrypted to a Cloud project and decoded on the *server*; the verdict inside it —
 * `MEETS_DEVICE_INTEGRITY` and its neighbours — is the server's to weigh. The moment the app
 * branches on one, the decision runs on the device the attacker owns, and an `if` on a device
 * the attacker owns is an `if` the attacker removes. The same goes for the older reflex this
 * item replaces: a `su` binary on the path, `test-keys` in `Build.TAGS`, a SafetyNet attestation
 * read client-side. All of it looks like security and none of it survives contact with somebody
 * who has `adb root`.
 *
 * ### A Play type that escaped its one file
 *
 * `PlayIntegrityAttestation` is the only file allowed to name `com.google.android.play`. That is
 * not tidiness: it is what keeps `IntegrityInterceptor`, the request hash and the error mapping
 * testable on a plain JVM, and it is what would let this be swapped for a different attestation
 * provider without touching anything above it. The Play Integrity API has no local test mode, so
 * every type that escapes into a second file is a decision that can only be checked on a device.
 *
 * ### A client that was never given the interceptor
 *
 * An endpoint asks for attestation with a marker header, and a client with no
 * `IntegrityInterceptor` sends that header to the server verbatim: no token, no error, and a
 * marker in the request announcing what was meant to happen. `NetworkModule` builds two clients
 * that share no configuration, and a third added later for downloads or images has the same
 * shape.
 *
 * ### A token in the log
 *
 * `HttpLoggingInterceptor` at `BODY` level prints every header. A Play Integrity token is not a
 * credential on its own, but it is bound to the request it accompanies, and a debug log prints
 * both — which is a replayable pair for as long as the token is fresh, sitting in logcat, on
 * every developer's machine and in every bug report.
 *
 * ### Why this reads source
 *
 * For the reason [CertificatePinningContractTest] does: "this builder was given that call" is
 * not a fact a class file records. Comments and string literals are blanked first
 * ([KotlinSource]), which this file needs more than its neighbours do — its own prose names
 * every symbol it bans, and so does the KDoc of half the integrity package.
 *
 * Known blind spot, stated rather than left to be discovered: because literals are blanked, a
 * verdict read out of a JSON *string* key would not be found by the first rule. The second rule
 * is what covers that case from the other side — decoding a token at all needs the Play library
 * or a JWE implementation, and neither can appear outside the one permitted file.
 */
class IntegrityAttestationContractTest {

    @Test
    fun `nothing in the app decides for itself whether the device is trustworthy`() {
        val verdicts = SourceTree.mainSources().flatMap { file ->
            val source = KotlinSource(file.readText())
            CLIENT_SIDE_VERDICT.findAll(source.code).map {
                "${file.repositoryPath()}:${source.lineOf(it.range.first)} — ${it.value}"
            }
        }

        assertTrue(verdicts.isEmpty()) {
            "These read or decide an integrity verdict inside the app. The verdict belongs to " +
                "the backend, which decodes the token through Google and can weigh it against " +
                "the account, the endpoint and the traffic it is seeing; a branch here runs on " +
                "the device under attack and is removed by the person attacking it. Send the " +
                "token and let the server answer — docs/root-detection.md has the server-side " +
                "half:\n" + verdicts.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `the rule that finds a client-side verdict can still find one`() {
        // Both directions, because this rule is a regex over blanked source and can go quiet
        // either way — matching nothing, or matching so much that it gets loosened until it
        // matches nothing.
        assertTrue(CLIENT_SIDE_VERDICT.containsMatchIn("if (verdict == MEETS_DEVICE_INTEGRITY)"))
        assertTrue(CLIENT_SIDE_VERDICT.containsMatchIn("payload.deviceRecognitionVerdict"))
        assertTrue(CLIENT_SIDE_VERDICT.containsMatchIn("fun isDeviceRooted(): Boolean"))
        assertTrue(CLIENT_SIDE_VERDICT.containsMatchIn("SafetyNetClient.attest(nonce, key)"))
        assertTrue(!CLIENT_SIDE_VERDICT.containsMatchIn("attestation.attest(requestHash)"))
        assertTrue(!CLIENT_SIDE_VERDICT.containsMatchIn("AttestationResult.Issued(token)"))
    }

    @Test
    fun `the Play Integrity library is named in exactly one file`() {
        val importers = SourceTree.mainSources()
            .filter { PLAY_INTEGRITY_IMPORT.containsMatchIn(KotlinSource(it.readText()).code) }
            .map { it.repositoryPath() }
            .sorted()

        // An equality rather than a ban, so the rule fails in both directions: a second file
        // naming a Play type, and the day the only one moves out from under it — at which point
        // this would otherwise pass by finding nothing.
        assertEquals(listOf(ATTESTATION_FILE), importers) {
            "`com.google.android.play` belongs to `PlayIntegrityAttestation` and nowhere else. " +
                "That boundary is what keeps the interceptor, the request hash and the error " +
                "mapping testable without the Android toolchain — the Play Integrity API has no " +
                "local test mode, so a decision that escapes into a second file is a decision " +
                "only a device can check. Take `IntegrityAttestation` instead. Found in: " +
                importers.joinToString(", ").ifEmpty { "nowhere, which means this rule is dead" }
        }
    }

    @Test
    fun `every OkHttp client in the app is given the attestation interceptor`() {
        val unattested = OkHttpClients.chains()
            .filter { !ATTESTED_CLIENT.containsMatchIn(it.calls) }
            .map { "${it.file}:${it.line}" }

        assertTrue(unattested.isEmpty()) {
            "These `OkHttpClient.Builder()` chains never add an integrity interceptor, so an " +
                "endpoint served by one of them sends `X-Require-Integrity` straight to the " +
                "server and no token. Nothing notices: the request succeeds, the marker is just " +
                "an unknown header, and the backend sees an unattested call it cannot " +
                "distinguish from a device that could not attest. The interceptor costs nothing " +
                "on a client whose endpoints are unmarked, which is why every client carries it " +
                "rather than only the ones that need it today:\n" +
                unattested.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `the rule that finds an unattested client can still find one`() {
        // This rule matches an argument *name*, which is weaker than the pinning rule's match
        // on a method name and is why it is pinned against fixtures here. It is also the only
        // thing available: the interceptor arrives as an injected parameter, so the chain text
        // holds a name and nothing else.
        assertTrue(ATTESTED_CLIENT.containsMatchIn(".addInterceptor(integrityInterceptor)"))
        assertTrue(ATTESTED_CLIENT.containsMatchIn(".addInterceptor(IntegrityInterceptor(a))"))
        assertTrue(!ATTESTED_CLIENT.containsMatchIn(".addInterceptor(loggingInterceptor)"))
        assertTrue(!ATTESTED_CLIENT.containsMatchIn(".authenticator(tokenAuthenticator)"))
    }

    @Test
    fun `the token header is redacted wherever request logging is configured`() {
        // The one rule here that reads raw text rather than [KotlinSource.code]: what it is
        // looking for is a *string literal*, and blanking literals is exactly what makes the
        // rest of this file trustworthy. The cost is that a `redactHeader` written inside a
        // comment would satisfy it, which is why the pattern requires the call's parentheses
        // and the header inside them rather than the word on its own.
        val logging = SourceTree.mainSources()
            .map { it to it.readText() }
            .filter { (_, text) -> LOGGING_INTERCEPTOR.containsMatchIn(text) }

        assertTrue(logging.isNotEmpty()) {
            "No `HttpLoggingInterceptor()` anywhere in src/main, so this rule reads nothing. If " +
                "logging moved, rewrite this rather than deleting it."
        }
        logging.forEach { (file, text) ->
            REDACTED_HEADERS.forEach { header ->
                assertTrue(redactionOf(header).containsMatchIn(text)) {
                    "${file.repositoryPath()} configures request logging and does not redact " +
                        "`$header`. At `BODY` level it prints every header; logcat is readable " +
                        "over `adb` and is what gets pasted into a bug report. An integrity " +
                        "token beside the request it is bound to is a replayable pair, and a " +
                        "bearer token is a live session."
                }
            }
        }
    }

    @Test
    fun `the cloud project configuration is still wired to the build`() {
        // Four things in a line — a properties file, a build script, a BuildConfig field and a
        // parser — and deleting any one of them leaves the others compiling and the app silently
        // unattested, which none of the rules above can see: every one of them is satisfied by
        // an app whose every `attest` answers NOT_CONFIGURED.
        val buildScript = File(SourceTree.root, DATA_BUILD_SCRIPT).readText()

        assertTrue(File(SourceTree.root, CLOUD_PROJECT_FILE).isFile) {
            "$CLOUD_PROJECT_FILE is gone. It is where the Cloud project is configured; without " +
                "it the build has nothing to read and no build can ever attest."
        }
        assertTrue(buildScript.contains(File(CLOUD_PROJECT_FILE).name)) {
            "$DATA_BUILD_SCRIPT no longer reads $CLOUD_PROJECT_FILE, so the project number " +
                "reaches no BuildConfig field and `IntegrityConfiguration.parse` is handed an " +
                "empty string forever."
        }
        assertTrue(buildScript.contains(BUILD_CONFIG_FIELD)) {
            "$DATA_BUILD_SCRIPT no longer writes `$BUILD_CONFIG_FIELD`, which `IntegrityModule` " +
                "reads."
        }
    }

    private companion object {
        const val ATTESTATION_FILE =
            "data/src/main/kotlin/com/kojo/boilerplate/core/security/integrity/" +
                "PlayIntegrityAttestation.kt"
        const val DATA_BUILD_SCRIPT = "data/build.gradle.kts"
        const val CLOUD_PROJECT_FILE = "gradle/play-integrity.properties"
        const val BUILD_CONFIG_FIELD = "INTEGRITY_CLOUD_PROJECT_NUMBER"

        /**
         * Reading a verdict, or deciding one locally.
         *
         * Three families, all matched as identifiers rather than as substrings so that a word
         * inside a longer name is not a finding:
         *
         * * the verdict constants and payload fields Google's `decodeIntegrityToken` returns,
         * * the decode call itself,
         * * the client-side root checks this item exists to replace — including SafetyNet, the
         *   deprecated API whose attestation people did read in the app.
         *
         * `attest`, `attestation` and `AttestationResult` are deliberately outside this set: the
         * app does request tokens, and a pattern loose enough to match those would ban the
         * feature it is guarding.
         */
        val CLIENT_SIDE_VERDICT = Regex(
            """\b(MEETS_(DEVICE|BASIC|STRONG|VIRTUAL)_INTEGRITY""" +
                """|deviceRecognitionVerdict|appRecognitionVerdict|appLicensingVerdict""" +
                """|playProtectVerdict|appAccessRiskVerdict|decodeIntegrityToken""" +
                """|SafetyNet\w*|RootBeer\w*|is(Device)?Rooted|detectRoot\w*)\b""",
        )

        val PLAY_INTEGRITY_IMPORT = Regex("""\bcom\.google\.android\.play\.""")

        /**
         * An `addInterceptor` whose argument names something integrity-shaped.
         *
         * Matched on the argument rather than on the call, because the interceptor arrives as
         * an injected parameter and the chain text holds nothing else. Weaker than the pinning
         * rule's match on `.certificatePinner(`, which is why it carries its own fixtures.
         */
        val ATTESTED_CLIENT =
            Regex("""\.addInterceptor\s*\(\s*[A-Za-z0-9_.]*[Ii]ntegrity[A-Za-z0-9_]*""")

        val LOGGING_INTERCEPTOR = Regex("""\bHttpLoggingInterceptor\s*\(\s*\)""")

        /** The two headers that must never reach logcat, as the source names them. */
        val REDACTED_HEADERS = listOf("Authorization", "INTEGRITY_TOKEN")

        /** A `redactHeader(…)` call naming [header], however the header is written. */
        fun redactionOf(header: String): Regex =
            Regex("""redactHeader\s*\([^)]*\b""" + Regex.escape(header) + """\b""")
    }
}
