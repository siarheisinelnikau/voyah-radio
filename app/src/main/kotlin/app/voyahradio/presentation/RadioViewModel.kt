package app.voyahradio.presentation

import androidx.lifecycle.ViewModel
import app.voyahradio.data.RadioCatalog
import app.voyahradio.data.RadioPreferences
import app.voyahradio.domain.CatalogOptions
import app.voyahradio.domain.Playback
import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** The station list and player of the main screen. */
internal class RadioViewModel(
    private val catalog: RadioCatalog,
    private val preferences: RadioPreferences,
) : ViewModel() {
    sealed interface Action {
        data class Play(val id: String, val tab: RadioTab) : Action
        data object Pause : Action
    }

    data class State(
        val tab: RadioTab,
        val query: String,
        val playback: Playback,
        val options: CatalogOptions,
        val favorites: Set<String>,
        val stations: List<Station>,
        val selected: Station?,
        val theme: String,
        val visualization: String,
        val smooth: Boolean,
    )

    private val actions = Channel<Action>(Channel.BUFFERED)
    val effects = actions.receiveAsFlow()
    private var tab = RadioTab.BELARUS
    private var query = ""
    private var playback = Playback(preferences.lastStation(), Playback.Status.READY)
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    fun restore(tab: RadioTab, query: String) {
        this.tab = tab
        this.query = query
        publish()
    }

    /** Reads the settings again when the screen comes back. */
    fun refresh() {
        publish()
        tab = state.value.tab
    }

    fun playbackChanged(value: Playback) {
        playback = value
        publish()
    }

    fun search(value: String) {
        if (query == value) return
        query = value
        publish()
    }

    fun chooseTab(value: RadioTab) {
        if (tab == value) return
        tab = value
        publish()
    }

    fun select(station: Station?) {
        if (station != null) actions.trySend(Action.Play(station.id, snapshot().tab))
    }

    fun togglePlayback() {
        if (playback.active()) actions.trySend(Action.Pause)
        else select(snapshot().selected)
    }

    fun step(direction: Int) {
        val current = snapshot()
        val stations = current.stations
        if (stations.isEmpty()) return
        val index = stations.indexOf(current.selected)
        val next = if (index < 0) (if (direction > 0) 0 else stations.lastIndex)
            else Math.floorMod(index + direction, stations.size)
        select(stations[next])
    }

    fun toggleFavorite(station: Station) {
        val favorites = preferences.favorites().toMutableSet()
        if (!favorites.remove(station.id)) favorites.add(station.id)
        preferences.saveFavorites(favorites)
        publish()
    }

    private fun publish() {
        mutableState.value = snapshot()
    }

    private fun snapshot(): State {
        val options = preferences.catalogOptions()
        val visibleTab = options.visibleTab(tab)
        val all = catalog.all()
        val favorites = preferences.favorites()
        val words = Station.searchWords(query)
        val stations = options.visibleStations(all, visibleTab, favorites).filter { it.matches(words) }
        val saved = catalog.find(playback.stationId)
        val selected = saved?.takeIf { options.shows(it) || playback.active() || playback.failed() }
            ?: stations.firstOrNull()
            ?: RadioTab.entries.asSequence().filterNot(options.hiddenTabs::contains)
                .firstNotNullOfOrNull { options.visibleStations(all, it, favorites).firstOrNull() }
        return State(visibleTab, query, playback, options, favorites, stations, selected, preferences.theme(),
            preferences.visualization(), preferences.smoothVisualization())
    }
}
