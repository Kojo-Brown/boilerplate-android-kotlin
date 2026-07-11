package com.kojo.boilerplate.feature.signin

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.auth.GoogleAuthRepository
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
) : ViewModel() {

    private val _uiState = MutableStateFlow<GoogleSignInUiState>(GoogleSignInUiState.Idle)
    val uiState: StateFlow<GoogleSignInUiState> = _uiState.asStateFlow()

    fun signIn(activityContext: Context) {
        if (_uiState.value is GoogleSignInUiState.Loading) return
        viewModelScope.launch {
            _uiState.update { GoogleSignInUiState.Loading }
            googleAuthRepository.signIn(activityContext)
                .onSuccess { user ->
                    _uiState.update { GoogleSignInUiState.Success(user) }
                }
                .onFailure { throwable ->
                    when (throwable) {
                        is GetCredentialCancellationException ->
                            _uiState.update { GoogleSignInUiState.Idle }
                        else ->
                            _uiState.update {
                                GoogleSignInUiState.Error(
                                    throwable.message ?: "Sign-in failed",
                                )
                            }
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

    fun clearError() {
        _uiState.update { state ->
            if (state is GoogleSignInUiState.Error) GoogleSignInUiState.Idle else state
        }
    }
}
