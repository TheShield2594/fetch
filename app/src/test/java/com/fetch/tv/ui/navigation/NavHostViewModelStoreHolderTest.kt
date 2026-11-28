package com.fetch.tv.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** The NavHost's ViewModels are cleared when its branch leaves composition (#809). */
class NavHostViewModelStoreHolderTest {
    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun `releasing the store clears its view models and the next acquire is fresh`() {
        val holder = NavHostViewModelStoreHolder()
        val store = holder.acquire()
        assertSame(store, holder.acquire())
        val probe = ViewModelProvider(owner(store))[Probe::class.java]

        holder.release(store)

        assertTrue(probe.cleared)
        assertNotSame(store, holder.acquire())
    }

    @Test
    fun `a stale release leaves the current store alone`() {
        val holder = NavHostViewModelStoreHolder()
        val first = holder.acquire()
        holder.release(first)
        val second = holder.acquire()
        val probe = ViewModelProvider(owner(second))[Probe::class.java]

        holder.release(first)

        assertFalse(probe.cleared)
        assertSame(second, holder.acquire())
    }

    private fun owner(store: ViewModelStore) = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = store
    }
}
