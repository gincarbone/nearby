package com.nearby.presentation.screens.connected

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearby.domain.model.Peer
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.PeerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ConnectedUiState(
    val syncedPeers: List<Peer> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class ConnectedViewModel @Inject constructor(
    private val peerRepository: PeerRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    val uiState: StateFlow<ConnectedUiState> = peerRepository.getSyncedPeers()
        .map { peers ->
            ConnectedUiState(
                syncedPeers = peers,
                isLoading = false
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ConnectedUiState()
        )

    fun getOrCreateConversation(peerId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val conversation = conversationRepository.getOrCreateConversation(peerId)
            withContext(Dispatchers.Main) {
                onReady(conversation.id)
            }
        }
    }
}
