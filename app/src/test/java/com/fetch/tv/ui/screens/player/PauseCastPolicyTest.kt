package com.fetch.tv.ui.screens.player

import com.fetch.tv.domain.model.MetaCastMember
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseCastPolicyTest {

    private val lead = MetaCastMember(name = "Ana Reyes", character = "Captain", photo = "a.jpg", tmdbId = 1)
    private val second = MetaCastMember(name = "Tom Hale", character = "Pilot", photo = "t.jpg", tmdbId = 2)

    @Test
    fun `guest stars follow the main cast in billing order`() {
        val guest = MetaCastMember(name = "Lia Moss", character = "Stranger", photo = "l.jpg", tmdbId = 9)

        assertEquals(listOf(lead, second, guest), pauseOverlayCast(listOf(lead, second), listOf(guest)))
    }

    @Test
    fun `a guest already in the main cast is listed once`() {
        val sameId = lead.copy(character = "Captain (young)")
        val sameName = MetaCastMember(name = " tom hale ", tmdbId = null)

        // The merged entry takes the episode's character.
        assertEquals(
            listOf(lead.copy(character = "Captain (young)"), second),
            pauseOverlayCast(listOf(lead, second), listOf(sameId, sameName))
        )
    }

    @Test
    fun `a guest with the same TMDB id under another name is listed once`() {
        val sameIdNewName = lead.copy(name = "Ana R.", character = null)

        assertEquals(listOf(lead, second), pauseOverlayCast(listOf(lead, second), listOf(sameIdNewName)))
    }

    @Test
    fun `namesakes with different TMDB ids are both shown`() {
        val namesake = lead.copy(character = "Stranger", photo = "n.jpg", tmdbId = 77)

        assertEquals(listOf(lead, second, namesake), pauseOverlayCast(listOf(lead, second), listOf(namesake)))
    }

    @Test
    fun `a guest whose main-cast entry is outside the row keeps a guest slot`() {
        val main = (1..20).map { MetaCastMember(name = "Regular $it", tmdbId = it) }
        val recurring = MetaCastMember(name = "Regular 15", character = "Guest role", photo = "g.jpg", tmdbId = 15)

        assertEquals(main.take(6) + recurring, pauseOverlayCast(main, listOf(recurring), limit = 7))
    }

    @Test
    fun `a photographed guest fills in a names-only main-cast entry`() {
        val namesOnly = MetaCastMember(name = "Ana Reyes")
        val guest = lead.copy(character = "Captain (this episode)")

        assertEquals(
            listOf(namesOnly.copy(photo = "a.jpg", character = "Captain (this episode)", tmdbId = 1), second),
            pauseOverlayCast(listOf(namesOnly, second), listOf(guest), limit = 7)
        )
    }

    @Test
    fun `a guest that duplicates a shown main entry frees its slot for the main cast`() {
        val main = (1..20).map { MetaCastMember(name = "Regular $it", tmdbId = it) }
        val guests = listOf(main[0], main[1], MetaCastMember(name = "Guest", tmdbId = 100))

        // Two of the three guests are already shown, so only one guest slot is needed.
        assertEquals(main.take(6) + guests[2], pauseOverlayCast(main, guests, limit = 7))
    }

    @Test
    fun `no guest stars returns the cast unchanged`() {
        val cast = listOf(lead, second)

        assertEquals(cast, pauseOverlayCast(cast, emptyList()))
    }

    @Test
    fun `a long main cast leaves up to half the row for guest stars`() {
        val main = (1..20).map { MetaCastMember(name = "Regular $it", tmdbId = it) }
        val guests = (1..3).map { MetaCastMember(name = "Guest $it", tmdbId = 100 + it) }

        val shown = pauseOverlayCast(main, guests, limit = 7)

        assertEquals(main.take(4) + guests, shown)
    }

    @Test
    fun `either side takes the room the other does not use`() {
        val main = (1..2).map { MetaCastMember(name = "Regular $it", tmdbId = it) }
        val guests = (1..10).map { MetaCastMember(name = "Guest $it", tmdbId = 100 + it) }

        assertEquals(main + guests.take(5), pauseOverlayCast(main, guests, limit = 7))
        assertEquals(main.take(1), pauseOverlayCast(main, emptyList(), limit = 1))
    }

    @Test
    fun `cast with photos replaces names-only cast`() {
        val namesOnly = listOf(MetaCastMember(name = "Ana Reyes"))

        assertEquals(listOf(lead), preferCastWithPhotos(namesOnly, listOf(lead)))
    }

    @Test
    fun `photos are merged per person when the current cast has only some`() {
        val withPhoto = MetaCastMember(name = "Ana Reyes", photo = "addon.jpg")
        val namesOnly = MetaCastMember(name = "Tom Hale")

        assertEquals(
            listOf(withPhoto, second),
            preferCastWithPhotos(listOf(withPhoto, namesOnly), listOf(lead, second))
        )
    }

    @Test
    fun `names-only cast does not replace cast with photos`() {
        val namesOnly = listOf(MetaCastMember(name = "Ana Reyes"))

        assertEquals(listOf(lead), preferCastWithPhotos(listOf(lead), namesOnly))
    }

    @Test
    fun `empty current takes incoming and empty incoming keeps current`() {
        assertEquals(listOf(lead), preferCastWithPhotos(emptyList(), listOf(lead)))
        assertEquals(listOf(lead), preferCastWithPhotos(listOf(lead), emptyList()))
    }
}
