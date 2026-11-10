package com.lagradost.cloudstream3

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.Event

/**
 * The official app's main activity class. This app's real screen (io.github.sherazi5110.cloudstream.MainActivity)
 * extends it, so `activity is MainActivity` holds for extensions, and its companion carries the app events
 * extensions use (StreamPlay, StremioX, Ultima…): they fire afterPluginsLoadedEvent after changing providers.
 */
abstract class MainActivity : AppCompatActivity() {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEventTv = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
    }
}
