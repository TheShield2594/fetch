package com.fetch.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.mediaserver.ServerConnection
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerLinkCode
import com.fetch.tv.data.mediaserver.ServerProvider
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.data.mediaserver.ServerSignIn
import com.fetch.tv.data.mediaserver.ServersUiState
import com.fetch.tv.data.mediaserver.serverFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerSignInState(
    val connecting: Boolean = false,
    val error: ServerFailure? = null
)

/** Progress of a link-code sign-in: a code to show, then a server to pick when there are several. */
data class ServerLinkState(
    val loading: Boolean = false,
    val code: ServerLinkCode? = null,
    val servers: List<ServerSignIn> = emptyList(),
    val connectingTo: String? = null,
    val expired: Boolean = false,
    val noServers: Boolean = false,
    val error: ServerFailure? = null
)

@HiltViewModel
class MediaServersViewModel @Inject constructor(
    private val repository: ServerRepository
) : ViewModel() {
    val uiState: StateFlow<ServersUiState> = repository.uiState

    val providers: List<ServerProvider>
        get() = repository.providers

    private val mutableSignIn = MutableStateFlow(ServerSignInState())
    val signIn: StateFlow<ServerSignInState> = mutableSignIn.asStateFlow()

    private val mutableLink = MutableStateFlow(ServerLinkState())
    val link: StateFlow<ServerLinkState> = mutableLink.asStateFlow()
    private var linkJob: Job? = null

    private val mutableRefreshing = MutableStateFlow<String?>(null)
    val refreshing: StateFlow<String?> = mutableRefreshing.asStateFlow()

    init {
        repository.ensureLoaded()
    }

    fun provider(connection: ServerConnection): ServerProvider? = repository.provider(connection)

    fun resetSignIn() {
        mutableSignIn.value = ServerSignInState()
    }

    fun connect(
        provider: ServerProvider,
        address: String,
        username: String,
        password: String,
        onConnected: (ServerConnection) -> Unit
    ) {
        if (mutableSignIn.value.connecting) return
        mutableSignIn.value = ServerSignInState(connecting = true)
        viewModelScope.launch {
            try {
                val connection = repository.connect(provider, address, username.trim(), password)
                mutableSignIn.value = ServerSignInState()
                onConnected(connection)
            } catch (error: CancellationException) {
                mutableSignIn.value = ServerSignInState()
                throw error
            } catch (error: ServerException) {
                mutableSignIn.value = ServerSignInState(error = error.failure)
            } catch (_: Exception) {
                mutableSignIn.value = ServerSignInState(error = ServerFailure.FAILED)
            }
        }
    }

    /** Shows a new code and waits for it to be approved; one reachable server is added right away. */
    fun startLink(provider: ServerProvider, onConnected: (ServerConnection) -> Unit) {
        linkJob?.cancel()
        mutableLink.value = ServerLinkState(loading = true)
        linkJob = viewModelScope.launch {
            try {
                val code = provider.startLink()
                mutableLink.value = ServerLinkState(code = code)
                val servers = awaitLink(provider, code)
                when {
                    servers == null -> mutableLink.value = ServerLinkState(expired = true)
                    servers.isEmpty() -> mutableLink.value = ServerLinkState(noServers = true)
                    servers.size == 1 -> connectLinked(provider, servers.single(), onConnected)
                    else -> mutableLink.value = ServerLinkState(servers = servers)
                }
            } catch (error: CancellationException) {
                // The repository also cancels a connect when the profile or its servers change.
                if (!isActive) throw error
                mutableLink.value = ServerLinkState(error = ServerFailure.FAILED)
            } catch (error: Exception) {
                val failure = error.serverFailure()
                mutableLink.value = if (failure == ServerFailure.AUTH_REQUIRED) {
                    ServerLinkState(expired = true)
                } else {
                    ServerLinkState(error = failure)
                }
            }
        }
    }

    /** Adds the server the user picked after a link found several. */
    fun chooseLinkedServer(provider: ServerProvider, signIn: ServerSignIn, onConnected: (ServerConnection) -> Unit) {
        if (mutableLink.value.connectingTo != null) return
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            try {
                connectLinked(provider, signIn, onConnected)
            } catch (error: CancellationException) {
                if (!isActive) throw error
                mutableLink.value = mutableLink.value.copy(connectingTo = null, error = ServerFailure.FAILED)
            } catch (error: Exception) {
                mutableLink.value = mutableLink.value.copy(connectingTo = null, error = error.serverFailure())
            }
        }
    }

    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        mutableLink.value = ServerLinkState()
    }

    /** Null when [code] expires before it is approved. A brief network drop keeps it waiting. */
    private suspend fun awaitLink(provider: ServerProvider, code: ServerLinkCode): List<ServerSignIn>? {
        var unreachable = 0
        while (System.currentTimeMillis() < code.expiresAtEpochMs) {
            delay(code.pollIntervalMs)
            val servers = try {
                provider.completeLink(code).also { unreachable = 0 }
            } catch (error: ServerException) {
                if (error.failure != ServerFailure.UNREACHABLE || ++unreachable >= MAX_UNREACHABLE_POLLS) throw error
                null
            }
            if (servers != null) return servers
        }
        return null
    }

    private suspend fun connectLinked(provider: ServerProvider, signIn: ServerSignIn, onConnected: (ServerConnection) -> Unit) {
        mutableLink.value = mutableLink.value.copy(connectingTo = signIn.serverName, error = null)
        val connection = repository.connect(provider, signIn)
        mutableLink.value = ServerLinkState()
        onConnected(connection)
    }

    fun refreshLibraries(connectionId: String, onFailure: (ServerFailure) -> Unit) {
        if (mutableRefreshing.value != null) return
        mutableRefreshing.value = connectionId
        viewModelScope.launch {
            try {
                repository.refreshLibraries(connectionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error.serverFailure())
            } finally {
                mutableRefreshing.value = null
            }
        }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) = repository.setEnabled(connectionId, enabled)

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) = repository.setCatalogMetadata(connectionId, enabled)

    fun setImportWatchState(connectionId: String, enabled: Boolean) = repository.setImportWatchState(connectionId, enabled)

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) =
        repository.setLibrarySelected(connectionId, libraryId, selected)

    fun remove(connectionId: String) = repository.remove(connectionId)

    private companion object {
        const val MAX_UNREACHABLE_POLLS = 3
    }
}
