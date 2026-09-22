plugins {
    id("boilerplate.android.library")
    id("boilerplate.hilt")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
}

/*
 * Certificate pinning: `gradle/certificate-pins.properties` in, two BuildConfig fields out.
 *
 * The pin set is deployment configuration rather than code — it names the public keys of
 * whichever server this build talks to, and it changes on a key rotation rather than on a code
 * change — so it lives in a file a human edits and the build reads. It is not a secret: an SPKI
 * hash is derived from a certificate that every TLS client on the internet is shown.
 *
 * Read through `providers.fileContents`, which registers the file as a build input. A bare
 * `File.readText()` would not, and the first symptom of that is a rotated pin set that does not
 * reach the APK because nothing invalidated the configuration cache.
 *
 * ## Why this validates before it interpolates
 *
 * `buildConfigField` takes the *source text* of the value, so everything here ends up inside a
 * Kotlin string literal in generated code. An unvalidated value carrying a quote, a backslash or
 * a `$` is an injection into a file nobody reads, reported as an error pointing at generated
 * source. So each half is constrained to a character set that cannot contain any of the three —
 * hosts and pins by regex, the expiry by being re-serialised from a parsed `Instant` rather than
 * passed through — and anything else fails the build here, where the message can name what in
 * the properties file caused it.
 *
 * This sits above `android { }` because a Kotlin script initialises its properties in the order
 * they are written, and `defaultConfig` reads these two.
 */
class CertificatePinSet(val serialisedHosts: String, val expiry: String)

val certificatePinsFile =
    rootProject.layout.projectDirectory.file("gradle/certificate-pins.properties")

/**
 * A host pattern in OkHttp's syntax: a DNS name, optionally prefixed by `*.` (exactly one label)
 * or `**.` (any depth). Deliberately narrower than "whatever OkHttp accepts" — `PinningPolicy`
 * is where a pattern's *meaning* is checked, and this exists only to bound what can be
 * interpolated into generated source.
 *
 * Unanchored, because both patterns are used with `Regex.matches`, which requires the whole
 * input to match. A trailing `$` immediately before a raw string's closing delimiter is also the
 * one place Kotlin's string interpolation and a regex anchor are spelled the same way.
 */
val certificateHostPattern =
    Regex("""(\*{1,2}\.)?[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*""")

/**
 * `sha256/` followed by exactly the Base64 of a 32-byte digest.
 *
 * OkHttp also accepts `sha1/` and this does not. A pin is a hash chosen so that a second key
 * hashing to it cannot be found, SHA-1 is the one of the two with a public collision, and there
 * is no reason to write a new SHA-1 pin. Rejecting it here means the message says so, rather
 * than the library quietly accepting it.
 */
val certificatePinPattern = Regex("""sha256/[A-Za-z0-9+/]{43}=""")

/** The `expires` value and the host entries of [certificatePinsFile], validated and serialised. */
fun readCertificatePins(text: String): CertificatePinSet {
    val properties = java.util.Properties().apply { load(java.io.StringReader(text)) }
    val expiry = (properties.remove("expires") as String?)?.trim().orEmpty()
    val fileName = certificatePinsFile.asFile.name

    val hosts = properties
        .map { entry -> entry.key.toString().trim() to entry.value.toString().trim() }
        .sortedBy { entry -> entry.first }
        .map { entry ->
            val host = entry.first
            require(certificateHostPattern.matches(host)) {
                "`$host` in $fileName is not a host pattern. Expected a DNS name, optionally " +
                    "prefixed by `*.` or `**.`."
            }
            val pins = entry.second.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            require(pins.isNotEmpty()) { "`$host` in $fileName has no pins." }
            pins.forEach { pin ->
                require(certificatePinPattern.matches(pin)) {
                    "`$pin` (for `$host`) is not a pin. Expected `sha256/` followed by the " +
                        "Base64 of a 32-byte digest — 43 characters and a `=`. " +
                        (if (pin.startsWith("sha1/")) "SHA-1 pins are not accepted here. " else "") +
                        "$fileName carries the openssl command that produces one."
                }
            }
            "$host=${pins.joinToString("|")}"
        }

    // Re-serialised from the parsed value rather than passed through, so that only an instant can
    // reach the generated source — and so that an expiry written in a form `Instant.parse` does
    // not accept fails here rather than on a device.
    val normalisedExpiry = if (expiry.isEmpty()) {
        ""
    } else {
        runCatching { java.time.Instant.parse(expiry).toString() }.getOrElse {
            throw GradleException(
                "`expires = $expiry` in $fileName is not an ISO-8601 instant such as " +
                    "`2027-03-01T00:00:00Z`.",
            )
        }
    }

    return CertificatePinSet(hosts.joinToString(";"), normalisedExpiry)
}

val certificatePins =
    readCertificatePins(providers.fileContents(certificatePinsFile).asText.orNull.orEmpty())


android {
    namespace = "com.kojo.boilerplate.data"

    defaultConfig {
        // Moved here from the application module with the code that reads it. `NetworkModule`
        // is the only consumer, so the field belongs to the module that builds the Retrofit
        // instances rather than to the one that happens to package them.
        buildConfigField("String", "BASE_URL", "\"https://api.example.com/v1/\"")

        // The pin set read above. Both fields are empty in an unmodified checkout, which is
        // what turns pinning off; `PinningPolicy.parse` is what reads them back.
        buildConfigField(
            "String",
            "CERTIFICATE_PINS",
            "\"${certificatePins.serialisedHosts}\"",
        )
        buildConfigField(
            "String",
            "CERTIFICATE_PIN_EXPIRY",
            "\"${certificatePins.expiry}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest, resources and assets through the
            // `com.android.tools.test_config.properties` file AGP only writes when this is on.
            // Without it `RobolectricTestRunner` cannot find a package name and fails every
            // test in `AppDatabaseMigrationTest` before a line of it runs.
            isIncludeAndroidResources = true
        }
    }
}

/*
 * The gate that stops an unpinned build being released.
 *
 * An empty pin set is the right default for this repository — the pins would have to name the
 * keys of a server that does not exist, and wrong pins are worse than no pins — but it is the
 * wrong thing to ship, and "add the pins before release" written in a README is a step that gets
 * skipped exactly once.
 *
 * Wired to `compileReleaseKotlin` rather than to `assembleRelease`, because this is a library:
 * `:app:assembleRelease` never runs a task named `assembleRelease` here, it consumes this
 * module's release variant. Debug is deliberately exempt — it is what CI assembles, and it is
 * what a proxy can be put in front of.
 *
 * `tasks.matching` rather than `tasks.named`: if AGP renames the task this becomes inert rather
 * than breaking every build, which is the failure to prefer for a gate that fires on a path
 * nothing in this repository exercises.
 */
val checkCertificatePinsConfigured = tasks.register("checkCertificatePinsConfigured") {
    description = "Fails a release build whose certificate pin set is empty."
    group = "verification"

    // Captured as locals so the task action holds two strings rather than the project.
    val serialisedHosts = certificatePins.serialisedHosts
    val configurationFile = certificatePinsFile.asFile.path
    inputs.property("pins", serialisedHosts)

    doLast {
        check(serialisedHosts.isNotEmpty()) {
            "This is a release build and $configurationFile declares no certificate pins, so " +
                "every connection falls back to system trust — which means any CA in the " +
                "device's store, including anything its owner or an MDM profile has added to " +
                "it, can terminate this app's TLS. Add the pin set for the host this build " +
                "talks to, or delete this task if pinning is deliberately not part of your " +
                "threat model. docs/certificate-pinning.md has the procedure."
        }
    }
}

tasks.matching { it.name == "compileReleaseKotlin" }.configureEach {
    dependsOn(checkCertificatePinsConfigured)
}

// The Room Gradle Plugin registers `room` on the project, not on the `android` extension, so
// this block has to sit outside `android { }`. The schema directory moves with the database.
room {
    schemaDirectory("$projectDir/schemas")
}

/*
 * Every implementation in the app: Room, DataStore, Retrofit/OkHttp and the platform
 * connectivity monitor, plus the Hilt modules that bind them to the interfaces `:core:domain`
 * and `:core:common` declare.
 *
 * Nothing depends on this module except `:app`, and `checkModuleDependencies` enforces that. A
 * screen that could see `UserDao` would eventually use it.
 */
dependencies {
    implementation(project(":core:auth"))
    api(project(":core:common"))
    api(project(":core:domain"))
    // `api` for the same reason `:core:domain` is: `PagedUserRepositoryImpl` implements an
    // interface declared there, so the interface is part of what this module offers `:app`.
    api(project(":core:paging"))

    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Paging 3. `paging-runtime` is the Android artifact — `Pager` is constructed here, in the
    // only module that can see a DAO — and `room-paging` is what lets a `@Query` return a
    // `PagingSource`. `paging-common` arrives transitively through both and through
    // `:core:paging`, which is where the contract that names `PagingData` lives.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Both DataStore flavours, because this module has both kinds of store: the untyped
    // key/value one behind the auth tokens and the theme, and the typed one behind
    // `UserPreferencesDataSource`.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)
    // The generated preferences schema. `implementation` rather than `api` on purpose: a
    // `UserPreferencesProto` is the shape of a file, and `UserPreferencesDataSource` maps it to
    // the Kotlin models in `:core:common` so that nothing above this module ever names one.
    implementation(project(":core:datastore-proto"))

    // WorkManager, plus the Hilt integration that lets a worker take constructor dependencies.
    // `hilt-compiler` is the second KSP processor in this module — Room's is the other — and it
    // is what writes the `HiltWorkerFactory` entry for `UserSyncWorker`. Without it the class
    // compiles and WorkManager fails to instantiate it at runtime.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver)
    // `CertificatePinningHandshakeTest` signs a CA and two leaf certificates in-process so that
    // the pinner is measured against a real handshake rather than against its own configuration.
    // Test-only, and it stays that way: nothing in `src/main` has a reason to mint a certificate.
    testImplementation(libs.okhttp.tls)
    // `AppDatabaseMigrationTest` opens real SQLite databases off `src/test`, which needs an
    // Android runtime in the JVM test JVM. This is the only module that has one, and the
    // dependency stays here rather than in `sharedTestDependencies()` for that reason: the
    // other fourteen modules would pay Robolectric's startup cost for nothing.
    testImplementation(libs.robolectric)

    // `UserDaoTest` stands up an in-memory Room database and reaches for
    // `InstrumentationRegistry`, which arrives with the Compose test rig's `androidx.test`
    // dependencies. This module draws nothing, so that is the only reason the artifact is here.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
