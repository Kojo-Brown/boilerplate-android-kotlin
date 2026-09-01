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
    // `AppDatabaseMigrationTest` opens real SQLite databases off `src/test`, which needs an
    // Android runtime in the JVM test JVM. This is the only module that has one, and the
    // dependency stays here rather than in `sharedTestDependencies()` for that reason: the
    // other thirteen modules would pay Robolectric's startup cost for nothing.
    testImplementation(libs.robolectric)

    // `UserDaoTest` stands up an in-memory Room database and reaches for
    // `InstrumentationRegistry`, which arrives with the Compose test rig's `androidx.test`
    // dependencies. This module draws nothing, so that is the only reason the artifact is here.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
