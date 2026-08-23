plugins {
    id("boilerplate.android.library")
    id("boilerplate.hilt")
}

android {
    namespace = "com.kojo.boilerplate.core.common"
}

// Depends on no other module in this repository, by rule and in fact. Everything else may
// depend on it, which is only safe while that stays true — `checkModuleDependencies` is what
// keeps it true.
dependencies {
    // `api` rather than `implementation`: `Flow`, `CoroutineScope` and `CoroutineDispatcher`
    // are all over this module's public signatures, so every consumer needs them to compile
    // against it.
    api(libs.kotlinx.coroutines.android)

    // `retryWithBackoff` decides whether an HTTP status is worth retrying, which means naming
    // `retrofit2.HttpException`. It appears only inside the function bodies, so it stays off
    // the consumers' compile classpath.
    implementation(libs.retrofit)

    // Building an `HttpException` to retry against needs a `retrofit2.Response`, which needs
    // an OkHttp `ResponseBody`.
    testImplementation(libs.okhttp)
}
