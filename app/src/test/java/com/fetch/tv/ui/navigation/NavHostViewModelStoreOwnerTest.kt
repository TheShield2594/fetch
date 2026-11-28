package com.fetch.tv.ui.navigation

import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The NavHost's owner must build ViewModels with constructor arguments, as `hiltViewModel()` does
 * for PlaybackAvailabilityProvider above the destinations (#809 review): a bare
 * ViewModelStoreOwner only gets the no-argument factory.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class NavHostViewModelStoreOwnerTest {
    class WithSavedState(val handle: SavedStateHandle) : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun `builds a SavedStateHandle view model in the branch store and clears it with the store`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val store = ViewModelStore()
        val owner = NavHostViewModelStoreOwner(activity, store)

        val model = ViewModelProvider(owner)[WithSavedState::class.java]

        assertNotNull(model.handle)
        store.clear()
        assertTrue(model.cleared)
    }
}
