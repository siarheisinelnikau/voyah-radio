package app.voyahradio.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.voyahradio.data.RadioCatalog
import app.voyahradio.data.RadioPreferences
import app.voyahradio.data.StreamProbe
import app.voyahradio.domain.Availability
import app.voyahradio.domain.CatalogOptions
import app.voyahradio.domain.Playback
import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.Collections

internal class RadioViewModel(
    private val catalog: RadioCatalog,
    private val preferences: RadioPreferences,
    private val probe: StreamProbe,
) : ViewModel() {
    sealed interface Action {
        data class Play(val id: String, val tab: RadioTab) : Action
        data object Pause : Action
        data object KeepOneTab : Action
        data object ChangeLanguage : Action
    }

    enum class Scan { IDLE, RUNNING, DONE, NO_NETWORK }

    data class State(
        val tab: RadioTab,
        val query: String,
        val playback: Playback,
        val options: CatalogOptions,
        val favorites: Set<String>,
        val stations: List<Station>,
        val selected: Station?,
        val group: RadioTab,
        val settingsStations: List<Station>,
        val settings: Boolean,
        val language: String,
        val theme: String,
        val visualization: String,
        val smooth: Boolean,
        val scan: Scan,
        val checked: Int,
        val total: Int,
        val unavailable: Int,
        val unknown: Int,
    )

    private val actions = Channel<Action>(Channel.BUFFERED)
    val effects = actions.receiveAsFlow()
    private var tab = RadioTab.BELARUS
    private var group = RadioTab.FAVORITES
    private var query = ""
    private var settings = false
    private var playback = Playback(preferences.lastStation(), Playback.Status.READY)
    private var scan = Scan.IDLE
    private var scanJob: Job? = null
    private var checked = 0
    private var unavailable = 0
    private var unknown = 0
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    fun restore(tab: RadioTab?, query: String, settings: Boolean) {
        this.tab = tab ?: RadioTab.BELARUS
        this.query = query
        this.settings = settings
        publish()
    }

    fun playbackChanged(value: Playback) {
        playback = value
        publish()
    }

    fun search(value: String) {
        query = value
        publish()
    }

    fun chooseTab(value: RadioTab) {
        tab = value
        publish()
    }

    fun openSettings(open: Boolean) {
        settings = open
        if (!open) cancelScan()
        publish()
    }

    fun select(station: Station) {
        actions.trySend(Action.Play(station.id, state.value.tab))
    }

    fun togglePlayback() {
        if (playback.active()) actions.trySend(Action.Pause)
        else state.value.selected?.let(::select)
    }

    fun step(direction: Int) {
        val (stations, selected) = state.value.let { it.stations to it.selected }
        if (stations.isEmpty()) return
        val current = stations.indexOf(selected)
        val next = if (current >= 0) Math.floorMod(current + direction, stations.size)
            else if (direction > 0) 0 else stations.lastIndex
        select(stations[next])
    }

    fun toggleFavorite(station: Station) {
        val favorites = preferences.favorites().toMutableSet()
        if (!favorites.remove(station.id)) favorites.add(station.id)
        preferences.saveFavorites(favorites)
        publish()
    }

    fun chooseGroup(value: RadioTab) {
        group = value
        publish()
    }

    fun language(value: String) {
        if (preferences.language() == value) return
        preferences.setLanguage(value)
        publish()
        actions.trySend(Action.ChangeLanguage)
    }

    fun theme(value: String) {
        preferences.setTheme(value)
        publish()
    }

    fun visualization(value: String) {
        preferences.setVisualization(value)
        publish()
    }

    fun smooth(value: Boolean) {
        preferences.setSmoothVisualization(value)
        publish()
    }

    fun showTab(tab: RadioTab, visible: Boolean) {
        val hidden = state.value.options.hiddenTabs
        val updated = if (visible) hidden - tab else hidden + tab
        if (updated.size >= RadioTab.entries.size) actions.trySend(Action.KeepOneTab)
        else preferences.saveHiddenTabs(updated)
        publish()
    }

    fun showStation(station: Station, visible: Boolean) {
        val hidden = state.value.options.hiddenStations
        preferences.saveHiddenStations(if (visible) hidden - station.id else hidden + station.id)
        publish()
    }

    fun move(station: Station, direction: Int) {
        val current = state.value
        if (group == RadioTab.FAVORITES) {
            val rows = current.settingsStations.toMutableList()
            val from = rows.indexOf(station)
            val to = from + direction
            if (from < 0 || to !in rows.indices) return
            Collections.swap(rows, from, to)
            preferences.saveFavoriteOrder(rows.map(Station::id))
        } else {
            preferences.saveOrder(current.options.move(catalog.all(), station, direction))
        }
        publish()
    }

    fun hideUnavailable(value: Boolean) {
        preferences.setHideUnavailable(value)
        publish()
    }

    fun toggleScan() {
        if (scan == Scan.RUNNING) {
            cancelScan()
            publish()
            return
        }
        val network = probe.validatedNetwork()
        if (network == null) {
            scan = Scan.NO_NETWORK
            publish()
            return
        }
        scan = Scan.RUNNING
        checked = 0
        unavailable = 0
        unknown = 0
        publish()
        scanJob = viewModelScope.launch {
            for (group in catalog.all().chunked(4)) {
                if (!probe.isCurrent(network)) break
                coroutineScope {
                    group.forEach { station ->
                        launch {
                            val observed = System.currentTimeMillis()
                            val result = runInterruptible(Dispatchers.IO) { probe.check(station, network) }
                            if (!probe.isCurrent(network)) return@launch
                            preferences.recordAvailability(station.id, result, observed)
                            checked++
                            if (result == Availability.UNAVAILABLE) unavailable++
                            if (result == Availability.UNKNOWN) unknown++
                            publish()
                        }
                    }
                }
            }
            scan = if (checked == catalog.all().size) Scan.DONE else Scan.NO_NETWORK
            publish()
        }
    }

    private fun cancelScan() {
        scanJob?.cancel()
        if (scan == Scan.RUNNING) scan = Scan.IDLE
    }

    private fun publish() {
        mutableState.value = snapshot()
    }

    private fun snapshot(): State {
        val options = preferences.catalogOptions()
        tab = options.visibleTab(tab)
        val all = catalog.all()
        val favorites = preferences.favorites()
        val words = Station.searchWords(query)
        val stations = options.visibleStations(all, tab, favorites).filter { it.matches(words) }
        val saved = catalog.find(playback.stationId)
        val selected = saved?.takeIf { options.shows(it) || playback.active() || playback.failed() }
            ?: stations.firstOrNull()
            ?: RadioTab.entries.asSequence().filterNot(options.hiddenTabs::contains)
                .firstNotNullOfOrNull { options.visibleStations(all, it, favorites).firstOrNull() }
        return State(
            tab, query, playback, options, favorites, stations, selected, group,
            options.stationsForTab(all, group, favorites).toList(), settings, preferences.language(),
            preferences.theme(), preferences.visualization(), preferences.smoothVisualization(),
            scan, checked, all.size, unavailable, unknown,
        )
    }
}
