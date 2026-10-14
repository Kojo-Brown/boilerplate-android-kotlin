package com.kojo.boilerplate.core.ui.udf

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.kojo.boilerplate.core.testing.MainDispatcherExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * [UdfViewModel.effects] — the stream six screens raise their navigate-once and show-once
 * decisions on, and until this file the one part of the UDF contract with no behavioural test
 * of its own. `UnidirectionalDataFlowContractTest` in `:app` pins its *shape*, that every view
 * model exposes `state` and `effects` and nothing else and that only the first is a
 * `StateFlow`; what the stream then does was asserted nowhere.
 *
 * The KDoc on [UdfViewModel] makes five claims about those emissions, each of them a reason
 * something was chosen over the obvious alternative: that order survives the `launch` inside
 * `emitEffect`, that an effect raised while nothing is collecting is buffered rather than
 * dropped, that a full buffer makes the producer wait rather than lose an effect, that the
 * stream survives its collector going away, and that an effect reaches exactly one of two
 * collectors. All five were prose, and the only coverage any of them had was
 * `GoogleSignInViewModelTest` asserting one *screen's* behaviour through them — so a regression
 * in the base class would have been found as a double navigation in whichever feature noticed
 * first, and debugged there.
 *
 * ### Why Turbine, when the rest of this repository collects into a list
 *
 * `backgroundScope.launch { flow.collect(list::add) }` and an `assertEquals` on the list is the
 * idiom everywhere else here, and for a question about a *value* it is the better one — no new
 * vocabulary for a reader to learn. What it cannot express is the question this file asks, which
 * is about a sequence: the list says what arrived by the time the assertion ran, so an effect
 * that arrives one dispatch later is invisible, and "and nothing else" cannot be stated at all.
 * Turbine's channel makes the sequence the subject — each `awaitItem` consumes one emission in
 * order, `expectNoEvents` asserts the absence of the next, and an unconsumed emission fails the
 * test at the end of the block rather than passing unnoticed. `docs/turbine.md` has the full
 * comparison and the rule this repository follows.
 *
 * ### One scheduler, Main included
 *
 * `emitEffect` posts through `viewModelScope`, which is `Dispatchers.Main.immediate`, so without
 * [MainDispatcherExtension] nothing here is delivered at all. The dispatcher is shared with
 * `runTest(mainDispatcher)` so that the test body and the view model's sends are on one clock:
 * with two, an `expectNoEvents` could pass because the send had not been dispatched yet rather
 * than because no effect was raised, which is an absence assertion that cannot fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UdfViewModelEffectsTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension(mainDispatcher)

    private val viewModel = AnnouncingViewModel()

    @Test
    fun `effects arrive in the order they were raised`() = runTest(mainDispatcher) {
        viewModel.effects.test {
            viewModel.onEvent(AnnouncingUiEvent.Announce(1))
            viewModel.onEvent(AnnouncingUiEvent.Announce(2))
            viewModel.onEvent(AnnouncingUiEvent.Announce(3))

            // The assertion is the ordering, and the reason it is worth making is the `launch`
            // inside `emitEffect`: three sends from three coroutines could in principle
            // interleave. They do not, because `viewModelScope` dispatches on
            // `Main.immediate` and `send` returns without suspending while the buffer has room
            // — so the effects are queued in the order the handler ran. A screen that showed
            // "saved" after "left the screen" would be this assertion failing.
            assertEquals(AnnouncingUiEffect.Announced(1), awaitItem())
            assertEquals(AnnouncingUiEffect.Announced(2), awaitItem())
            assertEquals(AnnouncingUiEffect.Announced(3), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `an effect raised while nothing is collecting is delivered when collection starts`() =
        runTest(mainDispatcher) {
            // The ordinary case on the sign-in screen rather than an edge one: the credential
            // picker is another Activity on top of it, so sign-in usually completes while this
            // screen is stopped and nothing is subscribed. A `MutableSharedFlow(replay = 0)`
            // here would accept the emission, drop it, and report success.
            viewModel.onEvent(AnnouncingUiEvent.Announce(1))

            viewModel.effects.test {
                assertEquals(AnnouncingUiEffect.Announced(1), awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `nothing is emitted for an event the view model decides nothing about`() =
        runTest(mainDispatcher) {
            viewModel.effects.test {
                viewModel.onEvent(AnnouncingUiEvent.Ignored)

                // State changed and no effect was raised, which is the distinction the whole
                // contract rests on: a counter the screen renders is state, and re-rendering it
                // after a rotation is correct. `expectNoEvents` is the half of that a list
                // collector cannot assert — an empty list reads the same whether nothing was
                // emitted or the collector had not run yet, and sharing one scheduler with
                // `Main` is what rules the second out here.
                expectNoEvents()
                assertEquals(1, viewModel.state.value.ignored)
            }
        }

    @Test
    fun `a consumed effect is not replayed to the collector that replaces it`() =
        runTest(mainDispatcher) {
            viewModel.onEvent(AnnouncingUiEvent.Announce(1))

            viewModel.effects.test {
                assertEquals(AnnouncingUiEffect.Announced(1), awaitItem())
            }

            // The composition after a rotation collects from scratch. It must not navigate a
            // second time — which is the entire reason this is a `Channel` and not the
            // `StateFlow` the screen renders.
            viewModel.effects.test {
                expectNoEvents()
            }
        }

    @Test
    fun `the stream outlives the collector that left and delivers what came after`() =
        runTest(mainDispatcher) {
            viewModel.effects.test {
                viewModel.onEvent(AnnouncingUiEvent.Announce(1))
                assertEquals(AnnouncingUiEffect.Announced(1), awaitItem())
            }

            // Raised with the first collector gone, exactly as a background save completing
            // behind a stopped screen is.
            viewModel.onEvent(AnnouncingUiEvent.Announce(2))

            viewModel.effects.test {
                // `receiveAsFlow` is what makes this pass. `consumeAsFlow` closes the channel
                // when its collector goes away, so the first rotation would leave a dead
                // stream behind and this would fail on a closed channel rather than on a
                // missing effect.
                assertEquals(AnnouncingUiEffect.Announced(2), awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `an effect reaches exactly one of two collectors`() = runTest(mainDispatcher) {
        turbineScope {
            val navigation = viewModel.effects.testIn(backgroundScope, name = "navigation")
            val analytics = viewModel.effects.testIn(backgroundScope, name = "analytics")

            viewModel.onEvent(AnnouncingUiEvent.Announce(1))

            // A `Channel` fans out to one receiver, and for a screen that is the feature: two
            // collectors of a navigation effect navigate twice. It is also the trade — a bus
            // every listener must see is a different problem with a different answer, which is
            // `AppEventBus` and its `SharedFlow`.
            assertEquals(AnnouncingUiEffect.Announced(1), navigation.awaitItem())
            analytics.expectNoEvents()

            navigation.cancel()
            analytics.cancel()
        }
    }

    @Test
    fun `no effect is lost when more are raised than the buffer holds`() =
        runTest(mainDispatcher) {
            // More effects than the channel buffers, with nothing collecting: the send that
            // finds the buffer full suspends until the drain below makes room. `trySend` in
            // `emitEffect`'s place would return a failure nobody reads and this run would be
            // one effect short — silently, and only for the screen unlucky enough to produce
            // a burst while stopped.
            repeat(MORE_THAN_THE_BUFFER) { index ->
                viewModel.onEvent(AnnouncingUiEvent.Announce(index))
            }

            viewModel.effects.test {
                repeat(MORE_THAN_THE_BUFFER) { index ->
                    assertEquals(AnnouncingUiEffect.Announced(index), awaitItem())
                }
                expectNoEvents()
            }
        }

    @Test
    fun `state is replayed to the collector that replaces one, and an effect is not`() =
        runTest(mainDispatcher) {
            viewModel.onEvent(AnnouncingUiEvent.Announce(1))

            // The first composition: it renders the count and it makes the announcement.
            viewModel.effects.test {
                assertEquals(AnnouncingUiEffect.Announced(1), awaitItem())
            }
            viewModel.state.test {
                assertEquals(1, awaitItem().announced)
            }

            // The composition after a rotation collects both again from scratch, and the one
            // question `docs/state-and-events.md` is built on splits them: if the screen were
            // destroyed and rebuilt right now, should this happen again? The count should still
            // read 1 — so it is state, and it is replayed in full. The announcement should not
            // be made a second time — so it is an effect, and there is nothing left to take.
            viewModel.state.test {
                assertEquals(1, awaitItem().announced)
            }
            viewModel.effects.test {
                expectNoEvents()
            }
        }

    private companion object {

        /**
         * One past `Channel.BUFFERED`'s actual capacity, which is 64 — the constant itself is a
         * capacity *mode* (`-2`) rather than a size, so it cannot be written as
         * `Channel.BUFFERED + 1`. The number is a floor rather than an exact figure:
         * `kotlinx.coroutines.channels.defaultBuffer` can raise the real capacity, and a run
         * where nothing suspended would still pass — it would simply be asserting ordering
         * again rather than the overflow path.
         */
        const val MORE_THAN_THE_BUFFER = 65
    }
}

/** What [AnnouncingViewModel] renders: two counts, so state and effects can be told apart. */
private data class AnnouncingUiState(val announced: Int = 0, val ignored: Int = 0)

private sealed interface AnnouncingUiEvent : UiEvent {

    /** Raises one [AnnouncingUiEffect.Announced] carrying [index], and counts it in the state. */
    data class Announce(val index: Int) : AnnouncingUiEvent

    /** Changes the state and decides nothing that should happen once. */
    data object Ignored : AnnouncingUiEvent
}

private sealed interface AnnouncingUiEffect : UiEffect {
    data class Announced(val index: Int) : AnnouncingUiEffect
}

/**
 * The smallest view model that exercises the base class: one event that raises an effect and
 * records it in state, one that only touches state.
 *
 * A purpose-built subject rather than one of the app's six screens, because the subject here is
 * [UdfViewModel] itself. Pointing these tests at `GoogleSignInViewModel` would put a credential
 * repository, a `Result` and a cancellation exception between the assertion and the behaviour
 * being asserted — and would leave the base class covered only for as long as that screen
 * happens to use it the same way. Feature modules test their own screens; this tests what all
 * of them inherit.
 *
 * The state is a plain `MutableStateFlow`. The other shape — `stateIn(viewModelScope,
 * WhileSubscribed(5_000), initial)` — is covered where it belongs, in `HomeViewModelTest` and
 * `StateAndEventSemanticsTest`: it is a property of `stateIn` rather than of this class, which
 * declares `state` abstract precisely so that each screen can choose.
 */
private class AnnouncingViewModel :
    UdfViewModel<AnnouncingUiState, AnnouncingUiEvent, AnnouncingUiEffect>() {

    private val _state = MutableStateFlow(AnnouncingUiState())
    override val state: StateFlow<AnnouncingUiState> = _state.asStateFlow()

    override fun onEvent(event: AnnouncingUiEvent) {
        when (event) {
            is AnnouncingUiEvent.Announce -> {
                _state.update { it.copy(announced = it.announced + 1) }
                emitEffect(AnnouncingUiEffect.Announced(event.index))
            }

            AnnouncingUiEvent.Ignored -> _state.update { it.copy(ignored = it.ignored + 1) }
        }
    }
}
