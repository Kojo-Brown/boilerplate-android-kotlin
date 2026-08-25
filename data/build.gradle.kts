plugins {
    id("boilerplate.android.library")
    id("boilerplate.hilt")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
}

android {
    namespace = "com.kojo.boilerplate.data"

    defaultConfig {
        // Moved here from the application module with the code that reads it. `NetworkModule`
        // is the only consumer, so the field belongs to the module that builds the Retrofit
        // instances rather than to the one that happens to package them.
        buildConfigField("String", "BASE_URL", "\"https://api.example.com/v1/\"")
    }

    buildFeatures {
        buildConfig = true
    }
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

    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

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

    // `UserDaoTest` stands up an in-memory Room database and reaches for
    // `InstrumentationRegistry`, which arrives with the Compose test rig's `androidx.test`
    // dependencies. This module draws nothing, so that is the only reason the artifact is here.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
