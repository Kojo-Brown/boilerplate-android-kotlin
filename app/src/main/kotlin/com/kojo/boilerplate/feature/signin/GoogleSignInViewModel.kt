package com.kojo.boilerplate.feature.signin

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.auth.GoogleAuthRepository
import com.kojo.boilerplate.core.ui.udf.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GoogleSignInViewModel @Inject constructor(
    private val googleAuthRepository: GoogleAuthRepository,
) : UdfViewModel<GoogleSignInUiState, GoogleSignInUiEvent, GoogleSignInUiEffect>() {

    /**
     * What the screen renders. A plain [MutableStateFlow] rather than a `stateIn` pipeline,
     * because this state is not derived from anything upstream — it is written by the two
     * handlers below and read by the screen. Every value it can hold is still true after the
     * Activity is recreated: a sign-in in flight is still in flight, and a signed-in user is
     * still signed in.
     */
    private val _state = MutableStateFlow<GoogleSignInUiState>(GoogleSignInUiState.Idle)
    override val state: StateFlow<GoogleSignInUiState> = _state.asStateFlow()

    override fun onEvent(event: GoogleSignInUiEvent) {
        when (event) {
            is GoogleSignInUiEvent.SignInClicked -> signIn(event.activityContext)
            GoogleSignInUiEvent.SignOutClicked -> signOut()
        }
    }

    private fun signIn(activityContext: Context) {
        if (_state.value is GoogleSignInUiState.Loading) return
        viewModelScope.launch {
            _state.update { GoogleSignInUiState.Loading }
            googleAuthRepository.signIn(activityContext)
                .onSuccess { user ->
                    _state.update { GoogleSignInUiState.Success(user) }
                    emitEffect(GoogleSignInUiEffect.SignedIn(user))
                }
                .onFailure { throwable ->
                    // The state after a failed sign-in is the state the screen started in: the
                    // sign-in button, ready to be pressed again. Why it failed is not part of
                    // that — it is one sentence to show once, which is what makes it an
                    // effect. Held as state it had to be cleared by hand once the snackbar was
                    // dismissed, and a rotation while the snackbar was up cancelled the clear
                    // and left the error in place for the next composition to show again.
                    _state.update { GoogleSignInUiState.Idle }
                    if (throwable !is GetCredentialCancellationException) {
                        emitEffect(
                            GoogleSignInUiEffect.SignInFailed(
                                throwable.message ?: "Sign-in failed",
                            ),
                        )
                    }
                }
        }
    }

    private fun signOut() {
        viewModelScope.launch {
            googleAuthRepository.signOut()
            _state.update { GoogleSignInUiState.Idle }
        }
    }
}
