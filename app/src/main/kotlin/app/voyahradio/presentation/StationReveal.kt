package app.voyahradio.presentation

import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station

/** What the station list shows: a new tab or search moves the list, a new station may bring it into view. */
internal data class ListFocus(val tab: RadioTab, val query: String, val station: String?)

internal sealed interface ListMove {
    /** Leave the list where it is. */
    data object Stay : ListMove

    /** Start a new search at the top. */
    data object Top : ListMove

    /**
     * Bring [row] into view with the row above it, unless it is fully visible: from the top of the list when the
     * list was just filled ([fromTop]), from where it is otherwise.
     */
    data class Reveal(val row: Int, val fromTop: Boolean, val animate: Boolean) : ListMove
}

internal object StationReveal {
    /** The row of [station] in a list of [columns] stations per row, or -1 when the list does not have it. */
    fun rowOf(stations: List<Station>, station: Station?, columns: Int): Int {
        val index = if (station == null) -1 else stations.indexOf(station)
        return if (index < 0) -1 else index / columns
    }

    /**
     * How the list follows a change from [previous] to [current], where [row] is the row of the station in the
     * current list. The radio opens on the station, a tab opens on it when it has it and at the top otherwise, and a
     * new station is brought into view while the list stays on the same tab and search.
     */
    fun move(previous: ListFocus?, current: ListFocus, row: Int): ListMove = when {
        previous == null || previous.tab != current.tab ->
            if (row < 0) ListMove.Top else ListMove.Reveal(row, fromTop = true, animate = false)
        previous.query != current.query -> ListMove.Top
        previous.station != current.station && row >= 0 -> ListMove.Reveal(row, fromTop = false, animate = true)
        else -> ListMove.Stay
    }

    /** The first row to show so that [row] has one row of context above it. */
    fun firstRow(row: Int): Int = maxOf(0, row - 1)

    /** Whether a row whose top is at [top] and whose tile is [height] tall fits in a viewport of [viewport]. */
    fun fullyVisible(top: Int?, height: Int, viewport: Int): Boolean = top != null && top >= 0 && top + height <= viewport
}
