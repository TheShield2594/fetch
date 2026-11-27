package com.fetch.tv.ui.util

import android.view.KeyEvent

/** True for the keys that act as OK on a TV remote or keyboard: D-pad centre, Enter and numpad Enter. */
fun isSelectKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
