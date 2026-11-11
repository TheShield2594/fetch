package com.fetch.tv.ui.screens.library

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.fetch.tv.data.mediaserver.ServersUiState
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.LibrarySourceMode
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.ui.components.posteroptions.LocalParentalTitleDialogHost
import com.fetch.tv.ui.theme.FetchTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.YearMonth
import java.time.ZoneId

/**
 * Audit C3's acceptance checks on a 960×540 dp TV screen, without an emulator: at least two full
 * poster rows show when the Library opens, and a title watched last month is reached on the
 * History tab within five presses of the remote.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class LibraryLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showLibrary(f: LibraryViewModelTestFixture) {
        val servers = mockk<LibraryServersViewModel>(relaxed = true) {
            every { servers } returns MutableStateFlow(ServersUiState())
            every { shelves } returns MutableStateFlow(null)
        }
        compose.setContent {
            // The poster menu's parental dialog needs Hilt, which this test has no component for.
            CompositionLocalProvider(LocalParentalTitleDialogHost provides {}) {
                FetchTheme { LibraryScreen(viewModel = f.viewModel, serversViewModel = servers, onNavigateToDetail = { _, _, _ -> }) }
            }
        }
        settle()
    }

    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    private fun press(keyCode: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        settle()
    }

    private fun localLibrary(titles: Int): LibraryViewModelTestFixture {
        val f = LibraryViewModelTestFixture(scope = LibraryListScope.WATCHLIST)
        f.source.value = LibrarySourceMode.LOCAL
        f.tabs.value = emptyList<LibraryListTab>()
        f.items.value = (1..titles).map { i ->
            LibraryEntry(
                id = "tt$i", type = "movie", name = "Saved title $i", poster = null, background = null, logo = null,
                description = null, releaseInfo = "2020", imdbRating = null, genres = emptyList(), addonBaseUrl = null,
                listKeys = setOf("local"), listedAt = i.toLong()
            )
        }
        return f
    }

    @Test
    fun `at least two full poster rows show when the Library opens`() {
        showLibrary(localLibrary(titles = 30))

        val root = compose.onRoot().fetchSemanticsNode()
        val height = root.size.height
        val cards = compose.onAllNodes(hasText("Saved title", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map(SemanticsNode::boundsInRoot)
        // A row is full when its posters (and their titles) are inside the screen.
        val fullRows = cards.filter { it.top >= 0f && it.bottom <= height }.map { it.top }.distinct()
        val density = Density(compose.activity)
        assertTrue(
            "full poster rows on entry at 960×540 dp: ${fullRows.size} (screen ${with(density) { height.toDp() }})",
            fullRows.size >= 2
        )
    }

    @Test
    fun `a title watched last month is reached in History within five presses`() {
        val f = localLibrary(titles = 4)
        val zone = ZoneId.systemDefault()
        val thisMonth = YearMonth.now(zone).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L
        val lastMonth = YearMonth.now(zone).minusMonths(1).atDay(10).atStartOfDay(zone).toInstant().toEpochMilli()
        f.watched.value = (1..3).map { i ->
            WatchedItem(contentId = "tt10$i", contentType = "movie", title = "This month $i", watchedAt = thisMonth + i)
        } + WatchedItem(contentId = "tt200", contentType = "movie", title = "Watched last month", watchedAt = lastMonth)
        showLibrary(f)

        // Focus starts on the Watchlist tab: right to In progress, right to History, open it, then
        // down past this month's row to last month's.
        val presses = listOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN
        )
        var reachedAfter: Int? = null
        presses.forEachIndexed { index, key ->
            press(key)
            if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
                // History loads off the main thread; a person presses on once its titles show.
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(hasText("Watched last month"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                settle()
            }
                if (reachedAfter == null && focusedHas("Watched last month")) reachedAfter = index + 1
        }
        assertTrue("last month's title wasn't focused within 5 presses", reachedAfter != null && reachedAfter!! <= 5)
    }

    /** The texts and descriptions of the focused element and its children. */
    private fun focusedLabel(): String = runCatching {
        val n = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()
        fun texts(node: SemanticsNode): List<String> =
            (node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.map { it.text }.orEmpty() +
                node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }.orEmpty()) +
                node.children.flatMap(::texts)
        texts(n).joinToString(" | ")
    }.getOrDefault("none")

    /** Whether the focused element shows [text], as its own text or description or a child's. */
    private fun focusedHas(text: String): Boolean = focusedLabel().contains(text)
}
