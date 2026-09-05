plugins {
    id("boilerplate.android.library")
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.kojo.boilerplate.core.datastore.proto"
}

/*
 * The schema module: one `.proto` file, the compiler that turns it into Java, and the lite
 * runtime that Java needs. It has no Kotlin source of its own and is not meant to acquire any.
 *
 * ### Why this is a module rather than four files in `:data`
 *
 * `:data` runs two KSP processors — Room's and Hilt's — and protoc's output is Java source that
 * has to exist before either of them reads the variant's sources. The protobuf plugin registers
 * that output as a *generated java source directory* on the variant, which wires `javac` to it
 * but not KSP, so `kspDebugKotlin` ends up consuming a directory it does not declare a
 * dependency on. Gradle 8 fails that as an implicit-dependency error, and when it does not, the
 * two tasks race. Keeping protoc in a module with no annotation processor removes the question
 * rather than answering it, which is what `now-in-android` does with its own `datastore-proto`
 * module for the same reason.
 *
 * The second reason is smaller and outlives the first: generated protobuf classes are a wire
 * format, not an API for the app to pass around. One module owning them makes "who can see a
 * `UserPreferencesProto`" a line in the root build file — today, only `:data`, which maps them
 * to the Kotlin models in `:core:common` at the boundary.
 */
protobuf {
    // protoc is a native binary, downloaded from Maven Central as a classified artifact and run
    // by the plugin — there is no toolchain to install on a build machine. `protobufProtoc` and
    // `protobufJavalite` share one version in the catalog, and that is load-bearing rather than
    // tidy: protobuf 4.x generated code calls `RuntimeVersion.validateProtobufGencodeVersion`
    // in its static initialiser, so a runtime older than the compiler that wrote the code
    // throws before any preference is read.
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }

    generateProtoTasks {
        // `forEach` rather than `configureEach`: the plugin reads `builtins` while it is still
        // configuring, to work out which directories to register as generated sources, so a
        // block that only runs when the task is realised can be read too late.
        all().forEach { task ->
            // `maybeCreate` rather than `register`, which throws on a name that already exists.
            // Whether the plugin has already created the `java` builtin depends on which of its
            // Android and java-plugin paths ran, and both end in the same place here: exactly
            // one `java` builtin carrying `lite`.
            //
            // `lite` is not an optimisation to revisit. The full runtime carries descriptors,
            // reflection and the text format — around a megabyte of dex and a `MethodHandle`
            // path that Android's verifier walks at class load — to support code generation and
            // introspection that a preferences file never asks for.
            task.builtins {
                maybeCreate("java").option("lite")
            }
        }
    }
}

dependencies {
    // `api`, not `implementation`: the generated classes carry `com.google.protobuf` types on
    // their public signatures — `parseFrom`, `writeTo`, `MessageLite` — so a consumer cannot
    // compile against them without the runtime on its own compile classpath.
    api(libs.protobuf.javalite)
}
