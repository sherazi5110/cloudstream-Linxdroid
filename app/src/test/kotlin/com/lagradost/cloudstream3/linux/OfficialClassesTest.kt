package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The official app's classes that big extensions use (found by scanning 101 extensions from four repositories
 * with `compat`): they must exist here with the official names and signatures.
 */
class OfficialClassesTest {
    @Test
    fun `classes from the official app that extensions use are here`() {
        // StreamPlay, StremioX, Ultima, CineStream: app events.
        val events = com.lagradost.cloudstream3.MainActivity.afterPluginsLoadedEvent
        var fired = false
        events += { fired = true }
        events(true)
        assertTrue(fired)
        // CineStream builds a SyncRepo around Simkl while loading; StreamPlay/TorraStream read AniList types.
        val simkl = com.lagradost.cloudstream3.syncproviders.AccountManager.simklApi
        assertEquals("simkl", simkl.idPrefix)
        assertEquals(null, com.lagradost.cloudstream3.syncproviders.SyncRepo(simkl).authUser())
        assertNotNull(Class.forName("com.lagradost.cloudstream3.syncproviders.providers.AniListApi\$CoverImage"))
        // Ultima, StreamPlay, StremioX manage other extensions through the official PluginManager.
        assertNotNull(com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline())
        // Ultima, WatchParty: player and continue-watching types.
        assertNotNull(com.lagradost.cloudstream3.ui.player.CSPlayerEvent.Play)
        // YoutubeProvider (official repository): NewPipe Extractor, set up like the official app.
        Platform.init(); Network.init()
        assertNotNull(org.schabi.newpipe.extractor.NewPipe.getDownloader())
        assertNotNull(org.schabi.newpipe.extractor.ServiceList.YouTube)
    }
}
