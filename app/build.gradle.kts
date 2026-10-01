// Imported rather than written out at the use site, for the reason data/build.gradle.kts gives:
// in a Kotlin build script `java` resolves to the `JavaPluginExtension` accessor the Java plugin
// contributes, not to the package root, so a `java.*` name written inline is a property access on
// that extension and does not compile.
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.Properties

plugins {
    id("boilerplate.android.application.compose")
    id("boilerplate.hilt")
}

/*
 * Play Store signing: gradle/upload-signing.properties plus three environment variables in, one
 * signing config out.
 *
 * SPEC.md Phase 11 item 5, whose words are "with secrets from the GitHub secret store only". The
 * word doing the work is *only*: there is no keystore in this repository, no signing block reading
 * gradle.properties or local.properties, no fallback to a file under the home directory, and no
 * Gradle property that carries a password. A signing key reachable from a clone leaks with the
 * clone, and a fallback is what turns "it built on my machine" into a release signed with a key
 * nobody chose.
 *
 * ## The split, and why a fingerprint is the half that is checked in
 *
 * The secret store holds what the key *is* — the keystore bytes and the two passwords. The
 * repository holds what the key *should be*: its alias and its certificate's SHA-256. Neither of
 * those is a secret. The fingerprint is printed by `apksigner verify --print-certs` for anybody
 * holding a copy of the APK, and Play Console shows it on a page of its own.
 *
 * Splitting it that way is what makes signing with the wrong key a build failure rather than a
 * discovery. Play identifies an upload by its certificate: a build signed with a key Play has not
 * been told about is rejected, and the *first* upload of a new app silently enrols whatever key it
 * arrives with. Secrets are opaque by design, so a rotated secret that points at a different
 * keystore than the one intended produces a green build and a properly signed artifact — signed
 * with the wrong key. The fingerprint is the only place that difference is visible, so the build
 * compares it and stops.
 *
 * ## Why nothing here throws
 *
 * An unconfigured, half-configured or mis-configured key becomes a *message* that
 * `checkUploadSigning` fails with, and that task gates `assembleRelease` and `bundleRelease` and
 * nothing else. Somebody running `assembleDebug` on a checkout whose upload-signing.properties is
 * filled in has no upload key, does not need one, and a `require` at configuration time would stop
 * them building at all. The release build is the only thing that needs the key, so it is the only
 * thing that fails without it.
 *
 * What must not happen is the third outcome, which is also AGP's default: a release variant with no
 * signing config does not fail the build, it writes `app-release-unsigned.apk` and exits 0. That
 * artifact cannot be installed and cannot be uploaded, and nothing in the log says so. It is the
 * one failure among the three deliberately-empty configuration files in `gradle/` whose symptom is
 * a shippable-looking artifact rather than a compile error, which is why it gets a task of its own.
 */
class UploadKeyIdentity(val alias: String, val certificateSha256: String)

/** The keystore and its two passwords, as they arrive from the secret store. */
class UploadKeySecrets(val keystore: ByteArray, val storePassword: String, val keyPassword: String)

/** A validated upload key: the declaration it matched, and where its keystore was written. */
class UploadKey(val identity: UploadKeyIdentity, val keystore: File, val secrets: UploadKeySecrets)

/**
 * Either an upload key or the reason there is none: exactly one of the two is non-null.
 *
 * Held to that by being built in exactly one place — [uploadSigning], below — rather than by a
 * private constructor and a pair of factories, which would need a companion object on a class
 * declared inside a build script. Both readers of it are in this file and both handle both fields.
 */
class UploadSigning(val key: UploadKey?, val reason: String?)

/**
 * Where the upload key's identity is declared.
 *
 * The `uploadSigningIdentity` property has exactly one caller: the `upload signing` job in
 * .github/workflows/ci.yml, which rehearses this whole path against a throwaway key it generates on
 * the runner. Without it the rehearsal would have to edit a tracked file to run, and a gate that
 * mutates the working tree in order to test itself is a gate nobody trusts. It carries a path and
 * never a secret — no password and no keystore can be passed on a command line here, which is the
 * property this item is about.
 */
val uploadSigningIdentityPath: String =
    providers.gradleProperty("uploadSigningIdentity").orNull?.trim()?.ifEmpty { null }
        ?: "gradle/upload-signing.properties"

val uploadSigningIdentityFile = rootProject.layout.projectDirectory.file(uploadSigningIdentityPath)

/** 64 hex characters, either case, with or without the `:` keytool puts between the bytes. */
val certificateFingerprintPattern = Regex("""[0-9A-F]{64}""")

/**
 * The keystore types tried, in order.
 *
 * A keystore from any keytool since JDK 9 is the first; the second is what `-storetype JKS` and
 * every keystore generated before then produces. Two readers rather than one because the JDK's
 * default type has changed underneath this exact use case once already, and the error a PKCS #12
 * reader gives a JKS file names neither format.
 */
val uploadKeystoreTypes = listOf("PKCS12", "JKS")

/**
 * Play's floor for an upload certificate's validity.
 *
 * Checked because an expiry is chosen once, at `keytool -genkeypair` time, and then never looked at
 * again — until the release that cannot be uploaded. keytool's default is 90 days; `-validity 10000`
 * is what every guide gives and it is a little over 27 years, so a key generated correctly clears
 * this by decades and a key generated by accident fails immediately.
 */
val playUploadKeyExpiryFloor = LocalDate.parse("2033-10-22").atStartOfDay(ZoneOffset.UTC).toInstant()

/** The subject every Android debug keystore's certificate has carried since the SDK shipped one. */
val debugCertificateCommonName = "CN=Android Debug"

/** Where the keystore is written: under `build/`, which .gitignore covers, and nowhere else. */
val uploadKeystoreFile = layout.buildDirectory.file("signing/upload-keystore.jks").get().asFile

/**
 * The alias and fingerprint [uploadSigningIdentityFile] declares, or `null` if it declares neither.
 *
 * Read through `providers.fileContents`, which registers the file as a build input. A bare
 * `File.readText()` would not, and the first symptom of that is an edited declaration that does not
 * reach the build because nothing invalidated the configuration cache.
 *
 * Both keys or neither: a fingerprint with no alias has no key to be compared against, and an alias
 * with no fingerprint is this file's whole comparison switched off while still looking configured.
 * Either one alone is a half-finished edit, so it fails rather than reading as "not configured".
 */
fun readUploadKeyIdentity(text: String, fileName: String): UploadKeyIdentity? {
    val properties = Properties().apply { load(StringReader(text)) }
    val alias = properties.getProperty("keyAlias")?.trim().orEmpty()
    val declared = properties.getProperty("certificateSha256")?.trim().orEmpty()
    if (alias.isEmpty() && declared.isEmpty()) return null

    require(alias.isNotEmpty()) {
        "$fileName declares `certificateSha256` and no `keyAlias`, so there is no key for it to be " +
            "compared against. Both or neither."
    }
    require(declared.isNotEmpty()) {
        "$fileName declares `keyAlias` and no `certificateSha256`. The fingerprint is what stops a " +
            "release being signed with the wrong key, and an alias on its own turns that check off " +
            "while looking configured. Both or neither."
    }

    val fingerprint = declared.filterNot { it == ':' || it.isWhitespace() }.uppercase()
    require(certificateFingerprintPattern.matches(fingerprint)) {
        "`certificateSha256 = $declared` in $fileName is not a SHA-256 certificate fingerprint. " +
            "Expected 64 hex characters, with or without `:` between the bytes, as " +
            "`keytool -list -v` and Play Console print it."
    }
    return UploadKeyIdentity(alias = alias, certificateSha256 = fingerprint)
}

/**
 * The three secrets, or `null` having collected the names of the ones that are unset into [missing].
 *
 * Read through `providers.environmentVariable` for the same reason the file goes through
 * `providers.fileContents`: it registers each variable as a build input, so a build that ran without
 * them does not stay cached once they are there.
 */
fun readUploadKeySecrets(missing: MutableList<String>): UploadKeySecrets? {
    fun secret(name: String): String? =
        providers.environmentVariable(name).orNull?.trim()?.ifEmpty { null }
            .also { if (it == null) missing.add(name) }

    val encoded = secret("UPLOAD_KEYSTORE_BASE64")
    val storePassword = secret("UPLOAD_KEYSTORE_PASSWORD")
    val keyPassword = secret("UPLOAD_KEY_PASSWORD")
    if (encoded == null || storePassword == null || keyPassword == null) return null

    // `getMimeDecoder` and not `getDecoder`: base64 pasted into a secret is routinely wrapped at 64
    // or 76 columns, and the strict decoder rejects a newline with an error that names neither the
    // variable nor the wrapping. The MIME decoder ignores anything outside the base64 alphabet,
    // which is the one place here where being permissive costs nothing — bytes that are not a
    // keystore fail the next check, with a message about the keystore.
    return UploadKeySecrets(
        keystore = Base64.getMimeDecoder().decode(encoded),
        storePassword = storePassword,
        keyPassword = keyPassword,
    )
}

/**
 * Writes the keystore where AGP can read it, and checks that it holds the key this commit declares.
 *
 * Written under `build/` rather than kept in memory because `SigningConfig.storeFile` is a `File`:
 * AGP hands the path to apksigner. Owner-only permissions, best effort — a CI workspace and a shared
 * checkout are both readable by whatever else runs there, and a filesystem without POSIX
 * permissions is not a reason to fail a release.
 *
 * Every check is one that would otherwise be made by Play, after the upload, or by a user whose app
 * can no longer be updated:
 *
 *  * the keystore opens with the store password;
 *  * it contains the declared alias — the mistake being a keystore holding keytool's default `key0`
 *    while the declaration says `upload`;
 *  * the key opens with the key password, which is a *second* password and is very often the same
 *    one, so a keystore where the two differ and a secret store where they do not is a failure that
 *    otherwise appears at signing time;
 *  * the certificate is the declared one;
 *  * it is not the debug certificate;
 *  * it outlives Play's expiry floor.
 */
fun materialiseUploadKeystore(
    identity: UploadKeyIdentity,
    secrets: UploadKeySecrets,
    destination: File,
    declaration: String,
): File {
    destination.parentFile.mkdirs()
    destination.writeBytes(secrets.keystore)
    runCatching {
        Files.setPosixFilePermissions(destination.toPath(), PosixFilePermissions.fromString("rw-------"))
    }

    var lastFailure: Throwable? = null
    val keystore = uploadKeystoreTypes.firstNotNullOfOrNull { type ->
        runCatching {
            KeyStore.getInstance(type).apply {
                destination.inputStream().use { load(it, secrets.storePassword.toCharArray()) }
            }
        }.onFailure { lastFailure = it }.getOrNull()
    }
    requireNotNull(keystore) {
        "UPLOAD_KEYSTORE_BASE64 did not decode to a keystore that " +
            uploadKeystoreTypes.joinToString(" or ") + " could open with UPLOAD_KEYSTORE_PASSWORD. " +
            "Either the bytes are not a keystore or that password is wrong; the last reader said: " +
            (lastFailure?.message ?: "nothing")
    }

    require(keystore.containsAlias(identity.alias)) {
        "The keystore holds no key called `${identity.alias}`. It holds " +
            keystore.aliases().toList().joinToString(", ") { "`$it`" }.ifEmpty { "no keys at all" } +
            ". `keyAlias` in $declaration has to be the alias inside the keystore, character " +
            "for character — keytool's default is `key0`."
    }
    runCatching { keystore.getKey(identity.alias, secrets.keyPassword.toCharArray()) }.getOrElse {
        throw IllegalArgumentException(
            "UPLOAD_KEY_PASSWORD does not open the key `${identity.alias}`. It is the password on " +
                "the key rather than on the keystore, and UPLOAD_KEYSTORE_PASSWORD did open the " +
                "file — so the two are not the same here. If this keystore is PKCS #12, they have " +
                "to be: the format has no separate key password, and keytool quietly sets it to " +
                "the store password whatever `-keypass` said. (${it.javaClass.simpleName})",
        )
    }

    val certificate = keystore.getCertificate(identity.alias) as? X509Certificate
    requireNotNull(certificate) {
        "`${identity.alias}` carries no X.509 certificate, so nothing about the key can be checked " +
            "and Play has nothing to identify the upload by. A keystore entry holding only a " +
            "private key cannot sign an APK."
    }

    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString("") { "%02X".format(it) }
    require(fingerprint == identity.certificateSha256) {
        "The keystore in UPLOAD_KEYSTORE_BASE64 is not the key this commit declares.\n" +
            "  declared in $declaration: ${identity.certificateSha256}\n" +
            "  in the keystore: $fingerprint\n" +
            "Signing with the wrong key is the one signing mistake Play cannot undo: an upload it " +
            "does not recognise is rejected outright, and the first upload of an app enrols " +
            "whatever key it arrives with. Fix whichever of the two moved."
    }
    require(!certificate.subjectX500Principal.name.contains(debugCertificateCommonName)) {
        "The upload key is the Android debug key (`$debugCertificateCommonName`). Play rejects a " +
            "debug-signed upload, and a debug key in a release signing config is a signing key " +
            "whose private half sits in every checkout of this repository."
    }
    require(certificate.notAfter.toInstant().isAfter(playUploadKeyExpiryFloor)) {
        "The upload certificate expires ${certificate.notAfter.toInstant()}, which is before " +
            "Play's floor of $playUploadKeyExpiryFloor. A key that expires is a key that cannot " +
            "ship another update, and an app signed by it has no upgrade path. Generate the key " +
            "with `-validity 10000`."
    }
    return destination
}

/** The upload key or the reason there is none, resolved once. Nothing in here throws. */
val uploadSigning: UploadSigning = run {
    val identity = runCatching {
        readUploadKeyIdentity(
            text = providers.fileContents(uploadSigningIdentityFile).asText.orNull.orEmpty(),
            fileName = uploadSigningIdentityPath,
        )
    }.getOrElse { return@run UploadSigning(key = null, reason = it.message ?: "$it") }

    if (identity == null) {
        return@run UploadSigning(
            key = null,
            reason = "Release signing is not configured: $uploadSigningIdentityPath declares " +
                "no upload key. " +
                "It is empty in this boilerplate on purpose — the values name a key that a real " +
                "Play Console app was created with, and this repository is linked to none. Its own " +
                "header says what goes in it, and docs/release-signing.md has the key " +
                "generation and the secret store.",
        )
    }

    val missing = mutableListOf<String>()
    val secrets = readUploadKeySecrets(missing)
    if (secrets == null) {
        return@run UploadSigning(
            key = null,
            reason = "$uploadSigningIdentityPath declares the upload key " +
                "`${identity.alias}`, but the key " +
                "itself is not in the environment: ${missing.joinToString(", ")} " +
                (if (missing.size == 1) "is" else "are") + " unset. CI sets these from repository " +
                "secrets of the same names; a local release build exports them by hand. They are " +
                "deliberately unreadable from anywhere inside the repository.",
        )
    }

    runCatching {
        val keystore = materialiseUploadKeystore(
            identity = identity,
            secrets = secrets,
            destination = uploadKeystoreFile,
            declaration = uploadSigningIdentityPath,
        )
        UploadSigning(key = UploadKey(identity, keystore, secrets), reason = null)
    }.getOrElse { UploadSigning(key = null, reason = it.message ?: "$it") }
}

android {
    namespace = "com.kojo.boilerplate"

    defaultConfig {
        applicationId = "com.kojo.boilerplate"
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        /*
         * Created only when there is a key to create it from, which is what makes the absence
         * visible: a `release` variant with a signing config full of nulls fails inside AGP's own
         * validation with a message about a keystore path, while no config at all is what
         * `checkUploadSigning` reports on in terms of the declaration and the secrets.
         *
         * The `debug` config is AGP's own and is deliberately not touched. It is the one key every
         * checkout has, `scripts/verify-apk.sh` asserts the debug APK carries exactly it, and the
         * `minified` build type below borrows it on purpose.
         */
        uploadSigning.key?.let { key ->
            create("release") {
                storeFile = key.keystore
                storePassword = key.secrets.storePassword
                keyAlias = key.identity.alias
                keyPassword = key.secrets.keyPassword

                // minSdk is 26, so every device that can install this app supports the block-based
                // schemes, and v1 is JAR signing: slow to verify, and the scheme whose weaknesses
                // (Janus, and an unsigned zip comment) the later ones exist to close. Written out
                // rather than left to AGP's defaults because these three decide what Play and the
                // installer will accept, and a default that moves is a release that stops
                // installing on one end of the range.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // `findByName` and not `getByName`: the config above exists only when the upload key
            // does, and this has to stay null rather than throw when it does not — see the header.
            // Null here is what `checkUploadSigning` is guarding, not a state that is tolerated.
            signingConfig = signingConfigs.findByName("release")
        }

        /*
         * The shrunk build CI can actually produce.
         *
         * R8 only runs on a minified variant, and the only minified variant here is `release` —
         * which this repository deliberately cannot build. `:data` fails `compileReleaseKotlin`
         * while `gradle/certificate-pins.properties` and `gradle/play-integrity.properties` are
         * empty, and they are empty because the values would have to name a real server's keys
         * and a real Play Console project. Both gates are right, and between them they mean
         * `app/proguard-rules.pro` is a file no build in this repository reads: a keep rule that
         * matches nothing, a rule that is missing, and a rule that works all produce the same
         * green CI. Putting fake pins in CI to get past that would defeat the gate whose whole
         * point is that nobody can drift past it.
         *
         * So this build type exists to give R8 somewhere to run. `initWith(release)` rather than a
         * second copy of the shrinker settings: the two must not drift, because a rule verified
         * here and absent from the release build is worse than no verification at all.
         *
         * `matchingFallbacks` is the load-bearing line. The fifteen library modules have only
         * `debug` and `release` variants, and it is `release` that cannot be built — so this
         * variant consumes their `debug` ones. That is also why the job in ci.yml that builds it
         * is the one that already ran `assembleDebug`: the library halves are the same outputs,
         * and R8 is the only new work.
         *
         * Debug signing so the result is installable — a shrunk APK is the only way to check a
         * `retrace`d stack trace against the mapping by hand, and `signingConfigs.debug` is the
         * one key every checkout of this repository has. It is not, and must not become, the
         * build that goes to Play: that is `release`, signed with the upload key, once the pins
         * and the cloud project are filled in.
         */
        create("minified") {
            initWith(getByName("release"))
            matchingFallbacks.add("debug")
            signingConfig = signingConfigs.getByName("debug")
        }

        /*
         * The build `:benchmark` measures.
         *
         * SPEC.md Phase 11 item 6. A startup number is only worth gating on if it is measured
         * against the build a user would install, and the three things that make a build fast are
         * all absent from `debug`: R8, no debuggability, and the baseline profile compiled into
         * `assets/dexopt`. Benchmarking `debug` is the most common way to produce a performance
         * gate that is green, stable, and measuring something nobody ships.
         *
         * `initWith(minified)` and not `initWith(release)` for the reason the block above exists:
         * `release` cannot be built in this repository while the certificate pins and the Play
         * Integrity project are deliberately empty. Inheriting from `minified` picks up R8 in full
         * mode, `app/proguard-rules.pro`, the `debug` signing config and the `debug` fallback for
         * the fifteen library modules in one line, and — the part that matters — guarantees the
         * shrinker configuration measured here is the same one `verify-r8-mapping.py` checks. Two
         * copies of it would drift, and a startup time measured against a different set of keep
         * rules than the shipped build uses is a number about nothing.
         *
         * `isProfileable` is the whole reason this is a fourth build type rather than a reuse of
         * `minified`. Macrobenchmark drives `am`, `dumpsys` and ART's profile commands against the
         * app from a separate process, and the platform only permits that on a build that has
         * opted in — either by being debuggable, which would destroy the measurement, or by being
         * profileable, which costs nothing at run time. Setting it on `minified` instead would mean
         * the artifact R8 is verified against is not the artifact AGP produces for `release`, which
         * is the one thing that block is careful about.
         *
         * It is not debuggable: `initWith(minified)` carries `release`'s `isDebuggable = false`
         * through, and `benchmark/build.gradle.kts` deliberately leaves Macrobenchmark's
         * `DEBUGGABLE` error unsuppressed so that a change to that fails the gate rather than
         * quietly halving the measured speed-up.
         */
        create("benchmark") {
            initWith(getByName("minified"))
            isProfileable = true

            /*
             * Restated rather than inherited, both of them, and the reason is the same for each.
             *
             * `initWith` is documented as copying the source build type's properties, and these
             * two are the ones whose absence is not a compile error. A missing `debug` fallback is
             * a configuration failure naming a variant of `:core:common` that does not exist; a
             * missing signing config is an unsigned APK that assembles fine and then cannot be
             * installed on the emulator, half an hour into the job. Both fixes would be these
             * lines, so they are here from the start.
             *
             * Saying either twice is harmless: a duplicate fallback is a no-op in AGP's variant
             * matching, and assigning the signing config the build type already has changes
             * nothing. Leaving them unsaid is not harmless, which is the asymmetry that decides it.
             */
            matchingFallbacks.add("debug")
            signingConfig = signingConfigs.getByName("debug")
        }

        /*
         * The build the baseline profile is *generated* from, and the one build type here whose
         * existence is forced by a detail rather than chosen.
         *
         * `BaselineProfileGenerator` records the names ART saw. Run against `benchmark` above,
         * those names are R8's: `a.b.c.d`. A profile of obfuscated names cannot be the file that is
         * checked in, because the next R8 run assigns different ones — AGP's own pipeline goes the
         * other way, taking source names from `src/main/baseline-prof.txt` and rewriting them
         * through the mapping as it packages the APK. So generation needs a variant that is
         * otherwise identical and not renamed.
         *
         * This is the same split the `androidx.baselineprofile` Gradle plugin makes for itself, and
         * the name is deliberately its vocabulary. The plugin is not used here because it derives
         * its generated build types from `release`, which this repository cannot build — see the
         * `minified` block above — so the split is made by hand instead.
         *
         * `isMinifyEnabled = false` turns off shrinking as well as renaming, which is more than
         * strictly needed and is correct anyway: a profile should name every class that runs at
         * startup, and R8 removing one would silently narrow the recording. Rules that no longer
         * match after shrinking are dropped by AGP when it packages the profile into the shrunk
         * APK, which is the right direction for that filtering to happen in.
         *
         * `debug` is not an option for this and the reason is the same silent-narrowing problem
         * from the other end: `debugImplementation` pulls in `ui-tooling` and the Compose test
         * manifest, so a profile generated there names classes no shipped build contains, and
         * `isDebuggable = true` changes what ART compiles in the first place.
         */
        create("nonMinifiedBenchmark") {
            initWith(getByName("benchmark"))
            isMinifyEnabled = false

            // The same three restated for the same reasons as above, `isProfileable` included:
            // `BaselineProfileRule` reads ART's profile for the app through the shell, and the
            // platform only allows that against a build that opted in. Without it the generator
            // fails on the device rather than at configuration time, which is the slowest place
            // to find out.
            isProfileable = true
            matchingFallbacks.add("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

/*
 * The gate. Without it, "no upload key" is not an error in any build: AGP writes
 * `app-release-unsigned.apk`, `bundleRelease` writes an unsigned `.aab`, both exit 0, and the first
 * thing that notices is Play.
 *
 * A separate task rather than a `require` at configuration time, because configuration is shared by
 * every build in the project and only the release build needs the key — see the header above.
 *
 * It declares no outputs, so it always runs. That is correct here: what it checks is the
 * environment, and a task that was up to date because nothing in the source tree changed would pass
 * on a machine where the secret has since been rotated out from under it.
 */
val uploadSigningReason = uploadSigning.reason
val uploadSigningSummary = uploadSigning.key?.let { key ->
    "alias `${key.identity.alias}`, certificate SHA-256 ${key.identity.certificateSha256}"
}

val checkUploadSigning = tasks.register("checkUploadSigning") {
    description = "Fails unless the Play upload key is configured, so no release can be built unsigned."
    group = "verification"

    doLast {
        if (uploadSigningReason != null) {
            throw GradleException(
                "$uploadSigningReason\n\nA release built without it would not fail: AGP would " +
                    "write an unsigned artifact and exit 0. This task is what turns that into a " +
                    "build failure.",
            )
        }
        // The fingerprint and the alias, and deliberately nothing else. Both are public — see
        // gradle/upload-signing.properties — and they are worth having in the log of the run that
        // produced an artifact, because "which key signed this build" is the question asked when
        // Play rejects one. Neither password is read here and neither is ever printed.
        logger.lifecycle("Play upload signing: $uploadSigningSummary")
    }
}

/*
 * What the gate gates.
 *
 * The first two are the lifecycle tasks a human or a workflow invokes, and they are the pair the
 * `upload signing` job in ci.yml asserts this task is still attached to — `--dry-run` prints the
 * task graph, so the wiring itself is checked on every pull request rather than assumed. That
 * matters because a `matching` block that stops matching fails silently, which is the same class of
 * quiet failure the R8 keep-rule checks exist for.
 *
 * The second two are AGP's own packaging tasks, included so that invoking one directly is gated as
 * well. They are not what the assertion in CI relies on: these names are AGP's to change, and when
 * they do, the two above still hold.
 */
val uploadSigningGatedTasks = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
)

tasks.matching { it.name in uploadSigningGatedTasks }.configureEach {
    dependsOn(checkUploadSigning)
}

// Phase 0 item 4. `scripts/verify-apk.sh` asserts that the assembled APK carries the package,
// versionCode, versionName, minSdk and targetSdk it is supposed to. Those expectations are
// written out of the build DSL here rather than copied into the script, so there is exactly one
// source of truth: a change to `defaultConfig` moves the expectation with it, and the check keeps
// verifying that AGP actually propagated the declared values into the artifact instead of
// comparing two hand-maintained copies.
//
// The values are read at configuration time and captured as task inputs, so the task stays
// configuration-cache compatible and re-runs when any of them changes.
val debugApkIdentityFile = layout.buildDirectory.file("apk-identity/debug.properties")

tasks.register("writeDebugApkIdentity") {
    description = "Writes the expected identity of the debug APK for scripts/verify-apk.sh."
    group = "verification"

    val debugBuildType = android.buildTypes.getByName("debug")
    // The debug variant's applicationId and versionName are the defaults plus whatever suffix
    // the build type declares. Neither is set today; reading them anyway means adding one later
    // does not silently turn this check into a false failure.
    val applicationId =
        requireNotNull(android.defaultConfig.applicationId) {
            "android.defaultConfig.applicationId is not set"
        } + (debugBuildType.applicationIdSuffix ?: "")
    val versionName =
        requireNotNull(android.defaultConfig.versionName) {
            "android.defaultConfig.versionName is not set"
        } + (debugBuildType.versionNameSuffix ?: "")
    val versionCode =
        requireNotNull(android.defaultConfig.versionCode) {
            "android.defaultConfig.versionCode is not set"
        }
    val minSdk =
        requireNotNull(android.defaultConfig.minSdk) { "android.defaultConfig.minSdk is not set" }
    val targetSdk =
        requireNotNull(android.defaultConfig.targetSdk) {
            "android.defaultConfig.targetSdk is not set"
        }

    inputs.property("applicationId", applicationId)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    inputs.property("minSdk", minSdk)
    inputs.property("targetSdk", targetSdk)
    outputs.file(debugApkIdentityFile)

    doLast {
        val destination = debugApkIdentityFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(
            """
            # Generated by :app:writeDebugApkIdentity — do not edit.
            applicationId=$applicationId
            versionCode=$versionCode
            versionName=$versionName
            minSdk=$minSdk
            targetSdk=$targetSdk
            """.trimIndent() + "\n",
        )
    }
}

/*
 * The assembly point, and the only module that may see everything.
 *
 * Two kinds of dependency are in this list and they are worth telling apart. `:feature:*`,
 * `:core:ui` and `:core:navigation` are here because this module's own code calls into them —
 * `AppNavHost` names every screen and every route. `:data` is here for the opposite reason:
 * nothing in `src/main` references a single type from it. It is on the list so that its Hilt
 * modules reach the component, which is what turns the interfaces the features inject into
 * objects at runtime. Removing it compiles and then fails at launch with a missing binding, so
 * it is deliberately not a dependency anyone should tidy away.
 */
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":data"))

    implementation(project(":feature:home"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:scanner"))
    implementation(project(":feature:signin"))
    implementation(project(":feature:textrecognition"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // `AppNavHost` names `SharedTransitionLayout` itself. It would arrive anyway as an `api`
    // dependency of `:core:ui`, which exposes the two scopes in `SharedElementTransition`'s
    // signature — declared here because this module's own source uses it, which is the same rule
    // every other line in this list follows.
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.collections.immutable)

    // WorkManager reaches `:app` for one reason: `BoilerplateApp` has to implement
    // `Configuration.Provider` and hand WorkManager the `HiltWorkerFactory`, because a worker
    // with constructor dependencies cannot be built by the default factory. That is a statement
    // about how the *application* is configured, so it belongs to the application module — and
    // it is the only WorkManager type named outside `:data`. The schedule itself, the worker and
    // the request stay there, behind `BackgroundSyncScheduler`.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    /*
     * ProfileInstaller: the runtime half of the baseline profile.
     *
     * AGP compiles `src/main/baseline-prof.txt` into `assets/dexopt/baseline.prof` inside the APK,
     * and that is as far as the build can take it — a file in the APK is not a profile ART has
     * accepted. On API 31 and above the platform installer reads it directly, but this app's
     * `minSdk` is 26, and on everything below 31 the only thing that moves the profile from assets
     * into ART's reference profile is this library's `androidx.startup` initialiser, running on
     * first launch.
     *
     * So without it the profile ships, nothing installs it on most of the supported range, and the
     * build stays green: there is no error, only an app that starts as slowly as it did before.
     * `StartupBenchmark.withBaselineProfile` uses `BaselineProfileMode.Require` precisely so that
     * state fails a gate instead of going unnoticed.
     *
     * Its initialiser merges into the `androidx.startup` provider that `AndroidManifest.xml`
     * already declares — the one that exists there to remove WorkManager's entry. Removing that
     * provider instead of the single `<meta-data>` node, as the comment beside it warns, would take
     * this initialiser down with it and produce exactly the silent failure above.
     *
     * Macrobenchmark also requires it in the app under test — 1.3.0 or newer — to reset compilation
     * state and drop the shader cache between iterations. Without it the second iteration of a cold
     * startup benchmark measures a partly warm app and reports numbers that are better than the
     * truth.
     */
    implementation(libs.androidx.profileinstaller)

    // Test-only on purpose. `StabilityContractTest` needs the Kotlin declaration model — `val`
    // vs `var`, sealed subclasses, generic type arguments — none of which survives into Java
    // reflection. Shipping it in the app would add ~1.7 MB to the APK to answer a question only
    // the build asks.
    testImplementation(libs.kotlin.reflect)
}
