package com.automate.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.automate.domain.model.Account
import com.automate.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<Account> = emptyList()
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllAccounts().collect { accounts ->
                _uiState.value = AccountsUiState(accounts = accounts)
            }
        }
    }

    fun addAccount(displayName: String, profileType: String) {
        viewModelScope.launch {
            repository.insertAccount(
                Account(
                    profileType = profileType,
                    displayName = displayName
                )
            )
        }
    }

    fun deleteAccount(id: Long) {
        viewModelScope.launch {
            val account = repository.getAccountById(id) ?: return@launch
            repository.deleteAccount(account)
        }
    }

    fun setActiveAccount(id: Long, profileType: String) {
        viewModelScope.launch {
            repository.setActiveAccount(id, profileType)
        }
    }
}
