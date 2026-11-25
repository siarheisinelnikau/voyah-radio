package app.voyahradio.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.voyahradio.data.RadioCatalog
import app.voyahradio.data.RadioPreferences
import app.voyahradio.data.StreamProbe
import app.voyahradio.domain.Availability
import app.voyahradio.domain.CatalogOptions
import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.Collections

/** The settings screen: appearance, visible tabs, availability check and the order of stations. */
internal class RadioSettingsViewModel(
    private val catalog: RadioCatalog,
    private val preferences: RadioPreferences,
    private val probe: StreamProbe,
) : ViewModel() {
    enum class Scan { IDLE, RUNNING, DONE, NO_NETWORK }

    data class State(
        val group: RadioTab,
        val language: String,
        val theme: String,
        val visualization: String,
        val smooth: Boolean,
        val options: CatalogOptions,
        val stations: List<Station>,
        val scan: Scan,
        val checked: Int,
        val total: Int,
        val unavailable: Int,
        val unknown: Int,
    )

    private var group = RadioTab.BELARUS
    private var scan = Scan.IDLE
    private var scanJob: Job? = null
    private var checked = 0
    private var unavailable = 0
    private var unknown = 0
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    fun selectGroup(value: RadioTab) {
        if (group == value) return
        group = value
        publish()
    }

    /** Returns whether the screen has to be recreated in the new language. */
    fun setLanguage(value: String): Boolean {
        if (preferences.language() == value) return false
        preferences.setLanguage(value)
        return true
    }

    /** Returns whether the screen has to be recreated in the new theme. */
    fun setTheme(value: String): Boolean {
        if (preferences.theme() == value) return false
        preferences.setTheme(value)
        return true
    }

    fun setVisualization(value: String) {
        preferences.setVisualization(value)
        publish()
    }

    fun setSmoothVisualization(value: Boolean) {
        preferences.setSmoothVisualization(value)
        publish()
    }

    /** Returns false, and keeps the tab, when it is the last visible one. */
    fun showTab(tab: RadioTab, visible: Boolean): Boolean {
        val hidden = preferences.catalogOptions().hiddenTabs.let { if (visible) it - tab else it + tab }
        if (hidden.size == RadioTab.entries.size) {
            publish()
            return false
        }
        preferences.saveHiddenTabs(hidden)
        publish()
        return true
    }

    fun showStation(station: Station, visible: Boolean) {
        val hidden = preferences.catalogOptions().hiddenStations
        preferences.saveHiddenStations(if (visible) hidden - station.id else hidden + station.id)
        publish()
    }

    fun move(station: Station, direction: Int) {
        val options = preferences.catalogOptions()
        if (group == RadioTab.FAVORITES) {
            val stations = options.stationsForTab(catalog.all(), group, preferences.favorites()).toMutableList()
            val index = stations.indexOf(station)
            val destination = index + direction
            if (index < 0 || destination !in stations.indices) return
            Collections.swap(stations, index, destination)
            preferences.saveFavoriteOrder(stations.map(Station::id))
        } else {
            preferences.saveOrder(options.move(catalog.all(), station, direction))
        }
        publish()
    }

    fun hideUnavailable(value: Boolean) {
        preferences.setHideUnavailable(value)
        publish()
    }

    /** Starts the availability check of every station, four at a time, or cancels the running one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleCheck() {
        if (scan == Scan.RUNNING) {
            cancelCheck()
            scan = Scan.IDLE
            publish()
            return
        }
        val network = probe.validatedNetwork()
        if (network == null) {
            scan = Scan.NO_NETWORK
            publish()
            return
        }
        checked = 0
        unavailable = 0
        unknown = 0
        scan = Scan.RUNNING
        val stations = catalog.all()
        val checks = Dispatchers.IO.limitedParallelism(4)
        val job = Job()
        scanJob = job
        stations.forEach { station ->
            viewModelScope.launch(job) {
                val observed = System.currentTimeMillis()
                val result = runInterruptible(checks) { probe.check(station, network) }
                if (!probe.isCurrent(network)) {
                    cancelCheck()
                    scan = Scan.NO_NETWORK
                    publish()
                    return@launch
                }
                preferences.recordAvailability(station.id, result, observed)
                if (result == Availability.UNAVAILABLE) unavailable++
                else if (result == Availability.UNKNOWN) unknown++
                if (++checked == stations.size) {
                    cancelCheck()
                    scan = Scan.DONE
                }
                publish()
            }
        }
        publish()
    }

    private fun cancelCheck() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun publish() {
        mutableState.value = snapshot()
    }

    private fun snapshot(): State {
        val options = preferences.catalogOptions()
        val all = catalog.all()
        return State(group, preferences.language(), preferences.theme(), preferences.visualization(),
            preferences.smoothVisualization(), options, options.stationsForTab(all, group, preferences.favorites()),
            scan, checked, all.size, unavailable, unknown)
    }
}
