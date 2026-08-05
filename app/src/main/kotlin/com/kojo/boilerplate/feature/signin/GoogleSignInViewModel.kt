package com.kojo.boilerplate.feature.signin

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.auth.GoogleAuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GoogleSignInViewModel @Inject constructor(
    private val googleAuthRepository: GoogleAuthRepository,
) : ViewModel() {

    /**
     * What the screen renders. A [StateFlow], because every value it can hold is still true
     * after the Activity is recreated: a sign-in in flight is still in flight, and a signed-in
     * user is still signed in.
     */
    private val _uiState = MutableStateFlow<GoogleSignInUiState>(GoogleSignInUiState.Idle)
    val uiState: StateFlow<GoogleSignInUiState> = _uiState.asStateFlow()

    /**
     * What the screen *does* once. A [Channel] rather than a [StateFlow], because a state is
     * replayed to whichever collector comes next and there is always a next collector — the
     * composition that replaces this one after a configuration change.
     *
     * Not a `SharedFlow` with no replay either. This screen has exactly one collector and it
     * goes away whenever the screen is stopped, and a `MutableSharedFlow(replay = 0)` drops
     * what it is given while nobody is subscribed. Sign-in finishes with this screen stopped
     * more often than not — the credential picker is another Activity on top of it — so that
     * is the ordinary case here, not the edge one. A `Channel` buffers instead, so the event
     * survives the gap and arrives when collection resumes; `receiveAsFlow` keeps delivery
     * exactly-once, so a collector that arrives after an event was taken sees nothing.
     */
    private val _events = Channel<GoogleSignInEvent>(Channel.BUFFERED)
    val events: Flow<GoogleSignInEvent> = _events.receiveAsFlow()

    fun signIn(activityContext: Context) {
        if (_uiState.value is GoogleSignInUiState.Loading) return
        viewModelScope.launch {
            _uiState.update { GoogleSignInUiState.Loading }
            googleAuthRepository.signIn(activityContext)
                .onSuccess { user ->
                    _uiState.update { GoogleSignInUiState.Success(user) }
                    _events.send(GoogleSignInEvent.SignedIn(user))
                }
                .onFailure { throwable ->
                    // The state after a failed sign-in is the state the screen started in: the
                    // sign-in button, ready to be pressed again. Why it failed is not part of
                    // that — it is one sentence to show once, which is what makes it an event.
                    // Held as state it had to be cleared by hand once the snackbar was
                    // dismissed, and a rotation while the snackbar was up cancelled the clear
                    // and left the error in place for the next composition to show again.
                    _uiState.update { GoogleSignInUiState.Idle }
                    if (throwable !is GetCredentialCancellationException) {
                        _events.send(
                            GoogleSignInEvent.SignInFailed(throwable.message ?: "Sign-in failed"),
                        )
                    }
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            googleAuthRepository.signOut()
            _uiState.update { GoogleSignInUiState.Idle }
        }
    }
}
