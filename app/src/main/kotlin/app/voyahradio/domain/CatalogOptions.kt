package app.voyahradio.domain

import java.util.Collections

class CatalogOptions(
    hiddenTabs: Set<RadioTab>,
    hiddenStations: Set<String>,
    unavailableStations: Set<String>,
    val hideUnavailable: Boolean,
    order: List<String>,
    favoriteOrder: List<String>,
) {
    val hiddenTabs = hiddenTabs.toSet()
    val hiddenStations = hiddenStations.toSet()
    val unavailableStations = unavailableStations.toSet()
    private val ranks = order.distinct().withIndex().associate { (index, id) -> id to index }
    private val favoriteRanks = favoriteOrder.distinct().withIndex().associate { (index, id) -> id to index }

    fun visibleTab(preferred: RadioTab): RadioTab =
        preferred.takeUnless(hiddenTabs::contains)
            ?: RadioTab.entries.firstOrNull { it !in hiddenTabs }
            ?: RadioTab.BELARUS

    fun shows(station: Station) =
        station.id !in hiddenStations && (!hideUnavailable || station.id !in unavailableStations)

    fun ordered(stations: List<Station>): List<Station> {
        // Legacy orders also contain stations in regions whose order was never changed.
        val lastRank = mutableMapOf<RadioTab, Int>()
        val changed = mutableSetOf<RadioTab>()
        for (station in stations) {
            val rank = ranks[station.id] ?: continue
            val previous = lastRank.put(station.group, rank)
            if (previous != null && rank < previous) changed.add(station.group)
        }
        if (changed.isEmpty()) return stations
        val overrides = stations.filter { it.group in changed }.groupBy(Station::group)
            .mapValues { (_, group) -> group.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }.iterator() }
        return stations.map { overrides[it.group]?.next() ?: it }
    }

    fun visibleStations(stations: List<Station>, tab: RadioTab, favorites: Set<String>): List<Station> =
        stationsForTab(stations, tab, favorites).filter(::shows)

    fun stationsForTab(stations: List<Station>, tab: RadioTab, favorites: Set<String>): List<Station> {
        val matching = ordered(stations).filter { tab.contains(it, favorites) }
        return if (tab == RadioTab.FAVORITES) matching.sortedBy { favoriteRanks[it.id] ?: Int.MAX_VALUE }
            else matching
    }

    fun move(stations: List<Station>, station: Station, direction: Int): List<String> {
        require(direction == -1 || direction == 1)
        val sorted = ordered(stations).toMutableList()
        val from = sorted.indexOf(station)
        if (from >= 0) {
            var to = from + direction
            while (to in sorted.indices) {
                if (sorted[to].group == station.group) {
                    Collections.swap(sorted, from, to)
                    break
                }
                to += direction
            }
        }
        return sorted.map(Station::id)
    }
}
