package com.kojo.boilerplate.core.coroutines

import javax.inject.Qualifier

/**
 * Qualifies the [kotlinx.coroutines.CoroutineScope] that lives as long as the process does.
 *
 * For work that must outlive the screen that started it — writing through to a cache after
 * the user navigates away, flushing analytics, kicking off a sync — where `viewModelScope`
 * would cancel it mid-flight. It is a [kotlinx.coroutines.SupervisorJob] carrying
 * [AppCoroutineExceptionHandler], so one failed task neither cancels the others nor crashes
 * the app.
 *
 * It is not a general-purpose escape from lifecycle scoping: work started here has no owner
 * watching it, so it must be short, bounded, and something the user will not wait on. Long
 * or retryable background work belongs in WorkManager, which survives process death.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
