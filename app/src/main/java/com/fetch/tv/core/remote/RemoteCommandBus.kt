package com.fetch.tv.core.remote

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Buttons the phone remote can press. */
enum class RemoteKey(val keyCode: Int) {
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
    OK(KeyEvent.KEYCODE_DPAD_CENTER),
    BACK(KeyEvent.KEYCODE_BACK),
    PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    REWIND(KeyEvent.KEYCODE_MEDIA_REWIND),
    FAST_FORWARD(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
    DELETE(KeyEvent.KEYCODE_DEL);

    companion object {
        fun fromName(raw: String?): RemoteKey? =
            raw?.let { name -> entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
    }
}

sealed interface RemoteCommand {
    data class Key(val key: RemoteKey) : RemoteCommand

    /** Text typed into whichever text field has focus on the TV. */
    data class Text(val text: String) : RemoteCommand

    /** Opens search on the TV and runs [query]. */
    data class Search(val query: String) : RemoteCommand
}

/**
 * Carries phone-remote input to the activity, which turns it into key events on the TV.
 * Only the app's own UI receives them; nothing is injected into other apps.
 */
@Singleton
class RemoteCommandBus @Inject constructor() {
    private val _commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<RemoteCommand> = _commands.asSharedFlow()

    private val _pendingSearch = MutableStateFlow<String?>(null)

    /** Search query from the phone, waiting for the search screen to pick it up. */
    val pendingSearch: StateFlow<String?> = _pendingSearch.asStateFlow()

    fun send(command: RemoteCommand): Boolean {
        if (command is RemoteCommand.Search) _pendingSearch.value = command.query
        return _commands.tryEmit(command)
    }

    fun consumePendingSearch(): String? {
        val query = _pendingSearch.value
        _pendingSearch.value = null
        return query
    }

    companion object {
        const val MAX_TEXT_LENGTH = 500
    }
}
