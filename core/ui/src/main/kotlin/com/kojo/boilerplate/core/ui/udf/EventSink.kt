package com.kojo.boilerplate.core.ui.udf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The one reference a composable is allowed to keep to its view model's [UdfViewModel.onEvent].
 *
 * ### What this replaces, and why it is not a bug fix
 *
 * `onClick = { viewModel.onEvent(Clicked) }` is the shape every screen in this app was written
 * in. The lambda captures `viewModel`, and a `ViewModel` is unstable to the Compose compiler —
 * an external class carrying mutable state, with no stability annotation to take on trust.
 *
 * Whether that costs anything depends on a compiler mode rather than on the code. With **strong
 * skipping** off, a lambda with an unstable capture is not memoised at all: it is a new instance
 * on every recomposition of the screen, every child handed one sees a parameter that changed,
 * and nothing below that screen can skip — a keystroke in the search field re-running the app
 * bar that does not read it. With strong skipping on, which has been the default since Kotlin
 * 2.0.20 and is what this project compiles with, the lambda *is* memoised, keyed on the view
 * model compared by identity — and since `hiltViewModel()` returns the same instance across
 * recompositions, the same lambda comes back and the children skip.
 *
 * So the screens were not slow. They were relying on a default — one that was opt-in two Kotlin
 * releases ago and that a `composeCompiler { }` block can still turn off — for a property their
 * own source did not have. Nothing failed while that held, and nothing would have failed when it
 * stopped; it would have surfaced as jank, months later, in a search field nobody had touched.
 * `docs/recomposition.md` has the full pass and the Layout Inspector procedure.
 *
 * ### Why a remembered reference has the property outright
 *
 * `viewModel::onEvent` has the function type `(E) -> Unit`, and function types are in the Compose
 * compiler's known-stable set. A lambda that captures *it* rather than the view model captures
 * only stable values, so it is memoised under either mode, and the stability propagates: a child
 * given one can pass it on and the lambdas built from it downstream are memoised too.
 *
 * [remember] keyed on the view model is what makes the sink itself one instance. A bound callable
 * reference is a new object each time it is evaluated, and while Kotlin gives those structural
 * equality, relying on that is relying on a detail of how references are compiled to keep a
 * screen's frame budget. One `remember` removes the question and the allocation together.
 *
 * ### Why this is a named function and not a line in each screen
 *
 * Because the mistake is invisible. Going back to `viewModel.onEvent(…)` compiles, behaves
 * identically, and reads fine in review. A named function is something a test can require:
 * `RecompositionContractTest` asserts that a composable names its view model only to read
 * `state`, to read `effects`, and to pass it here.
 *
 * ```
 * @Composable
 * fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
 *     val state by viewModel.state.collectAsStateWithLifecycle()
 *     val onEvent = rememberEventSink(viewModel)
 *     RefreshAction(inProgress = state.isRefreshing, onRefresh = { onEvent(RefreshClicked) })
 * }
 * ```
 *
 * @param viewModel the screen's view model. The returned sink is tied to this instance and is
 *   rebuilt only if it is ever replaced — which for a screen means a new [UdfViewModel] after the
 *   owning `NavBackStackEntry` is disposed, not a configuration change.
 */
@Composable
fun <E : UiEvent> rememberEventSink(viewModel: UdfViewModel<*, E, *>): (E) -> Unit =
    remember(viewModel) { viewModel::onEvent }
