package com.fetch.tv.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Holds the store that a root NavHost's back stack ViewModels live in. It is kept in the
 * activity's own store, so it survives a configuration change or a theme recreate.
 */
internal class NavHostViewModelStoreHolder : ViewModel() {
    private var store: ViewModelStore? = null

    fun acquire(): ViewModelStore = store ?: ViewModelStore().also { store = it }

    fun release(store: ViewModelStore) {
        if (this.store !== store) return
        this.store = null
        store.clear()
    }

    override fun onCleared() {
        store?.clear()
        store = null
    }
}

/**
 * A [ViewModelStoreOwner] for a NavHost whose NavController is remembered inside a branch that can
 * leave composition, such as the main UI while the profile picker shows (#809).
 *
 * The NavController keeps each back stack entry's ViewModels in the owner it is given, normally
 * the activity. Dropping the NavController never pops its entries, so their ViewModels (Home's,
 * with every collector and pipeline it runs) stayed alive for the life of the activity and kept
 * working for whichever profile came next. With this owner they are cleared when the branch
 * leaves composition, except across a configuration change, which keeps them as before.
 */
@Composable
internal fun rememberNavHostViewModelStoreOwner(activity: ComponentActivity): ViewModelStoreOwner {
    val holder = remember(activity) {
        ViewModelProvider(activity)[NavHostViewModelStoreHolder::class.java]
    }
    val store = remember(holder) { holder.acquire() }
    DisposableEffect(holder, store) {
        onDispose {
            if (!activity.isChangingConfigurations) holder.release(store)
        }
    }
    return remember(store) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }
}
