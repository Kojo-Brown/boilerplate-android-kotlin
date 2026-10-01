package com.kojo.boilerplate.benchmark

/*
 * Which app this module points at.
 *
 * Deliberately the only thing in this file, and deliberately free of every import: it is the one
 * file in `:benchmark` that `scripts/jvm-harness/run.sh` can compile, because the other two name
 * `androidx.benchmark` and `androidx.test.uiautomator` — Google Maven artifacts, which the agent's
 * environment cannot fetch. A constant that is wrong here is wrong in both tests at once, so
 * keeping it somewhere a compiler can see it offline is worth the separate file.
 */

/**
 * The application id of the `benchmark` and `nonMinifiedBenchmark` variants of `:app`.
 *
 * It is `android.defaultConfig.applicationId` with no suffix, because no build type in
 * `app/build.gradle.kts` sets `applicationIdSuffix` — `benchmark` is `initWith(minified)`, which is
 * `initWith(release)`, and `nonMinifiedBenchmark` is `initWith(benchmark)`; none of the four adds
 * one. Written out rather than read from the build because an instrumentation argument is the only
 * way a test module could be told, and a wrong value fails immediately and unmistakably:
 * Macrobenchmark cannot find the package and says so.
 */
internal const val TARGET_PACKAGE = "com.kojo.boilerplate"
