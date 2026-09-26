# Keep rules for the whole app. R8 runs in full mode — see `android.enableR8.fullMode` in
# gradle.properties — and docs/r8.md explains what that changes and how to check a rule still
# earns its place.
#
# Two things are worth knowing before adding to this file.
#
# **Most libraries ship their own rules and they arrive automatically.** An AAR's
# `consumer-rules.pro` and a JAR's `META-INF/proguard/*.pro` are merged into this build's
# configuration, so Retrofit, OkHttp, Room, Hilt, kotlinx.serialization, WorkManager, CameraX,
# ML Kit and Play Integrity need nothing here. Copying a rule out of a library's README when it
# already ships that rule is the most common way this file grows: the duplicate can never fail,
# so it is never removed, and the next reader cannot tell which lines are load-bearing.
# `build/outputs/mapping/<variant>/configuration.txt` is the merged set R8 actually used.
#
# **A rule that matches nothing looks exactly like a rule that works.** R8 does not warn about a
# `-keep` whose pattern names no class, so a package that was renamed or a member that never
# existed leaves a file full of protection that protects nothing — which is what this file was:
# it kept `com.kojo.boilerplate.navigation.**`, a package this repository has never had (the
# destinations are in `com.kojo.boilerplate.core.navigation`), and `CREATOR` fields on
# `**$$serializer` classes, which are serializers and have no `CREATOR`. Both lines were inert
# for the life of the file and nothing said so. `scripts/verify-r8-mapping.py` is the answer to
# that: it checks the rules below by their *effect* on the shrunk output, so a pattern that stops
# matching fails CI instead of going quiet.


# --- The app's own @Serializable types -------------------------------------------------------
#
# kotlinx.serialization's own rules cover most of this and cannot cover this part: a library rule
# cannot name our packages. Serializable *objects* are the ones at risk. `AppDestination.SignIn`
# and its three siblings are `data object`s, and nothing in the app ever writes `SignIn.INSTANCE`
# — Navigation looks the route up through `serializer()` at runtime — so in full mode R8 sees a
# static field with no reader and an object with no instantiation site, and the conditional rule
# kotlinx.serialization 1.7.3 ships (`-if @Serializable class ** { public static ** INSTANCE; }`)
# is not enough to hold it: the fix for exactly that case went upstream in 1.8.0, as an
# unconditional `-keepclassmembers` in `kotlinx-serialization-r8.pro`.
#
# So this is that rule, scoped to this app rather than to `**`. It can be deleted when
# `serialization` in gradle/libs.versions.toml reaches 1.8.0 or later, because the artifact will
# then bring it along.
#
# See https://github.com/Kotlin/kotlinx.serialization/issues/2861 and
# https://issuetracker.google.com/issues/379996140.
#
# Scoped to `com.kojo.boilerplate.**` and not to a sub-package on purpose. The serializable types
# live in two of them today — `core.navigation` for the routes and `core.network.model` for the
# DTOs — in two different Gradle modules, and the rule this replaces was broken precisely by
# naming one of those paths and then being left behind when it moved.
-keepclassmembers @kotlinx.serialization.Serializable class com.kojo.boilerplate.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}


# --- Protobuf, for the typed preferences store ------------------------------------------------
#
# `protobuf-javalite` is a plain JAR with no `META-INF/proguard` entry, so unlike every other
# dependency here it ships no rules at all and this one is ours to supply.
#
# Lite generated code carries no per-field methods for the runtime to call. `GeneratedMessageLite`
# describes its own layout with a `RawMessageInfo` — a packed string naming each field — and
# `MessageSchema` resolves those names against the class with `getDeclaredField` when it first
# serialises or parses a message. Rename the fields and that lookup throws; drop them and there is
# nothing to look up. Neither is visible until something reads a preference, which on this app's
# start-up path is before the first frame.
#
# Members only, and fields only: the generated methods are called directly from
# `UserPreferencesSerializer` and the DataStore flows, so tree shaking keeps what is reachable and
# is free to rename and inline the rest.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}


# --- What used to be here, and why it is not ---------------------------------------------------
#
# `-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends ViewModel`. The rule
# dates from the Hilt that looked a view model up by name: its generated module bound each one
# into a map with `@StringKey("com.kojo…HomeViewModel")` and `HiltViewModelFactory` matched that
# against `modelClass.getName()`, so renaming the class broke every screen with a
# `Cannot create an instance of class …` at navigation time. Hilt 2.57.2 does not: its
# `hiltViewModelKeys` is a `Map<Class<?>, Boolean>` keyed by class literals — read off the
# artifact's own bytecode, not from its documentation — and R8 rewrites a class literal with the
# class it names. Nothing reads a view model's name any more, so nothing needs it kept, and the
# rule was costing every view model in the app its obfuscation for a lookup that no longer exists.
#
# Hilt's own `proguard.txt` keeps what still does need it — the `@EntryPoint` interfaces
# `EntryPoints.get` casts to reflectively — and arrives on its own.


# --- Crash reports ---------------------------------------------------------------------------
#
# Line numbers are what makes a mapping file worth uploading: without them every frame in a
# retraced stack trace points at the class and method but not at the line, which is most of what a
# stack trace is for. `SourceFile` is what lets retrace name the file. R8 rewrites both through
# the mapping, so keeping the attributes costs no information about the source.
-keepattributes SourceFile,LineNumberTable

# ...and then hides the original file names from the shipped artifact, because the retrace side
# only needs the mapping. Without this, every class in the APK still carries its `.kt` file name.
-renamesourcefileattribute SourceFile
