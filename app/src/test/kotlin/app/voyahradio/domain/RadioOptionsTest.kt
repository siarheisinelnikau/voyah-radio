package app.voyahradio.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class RadioOptionsTest {
    private val radius = station("radius", RadioTab.BELARUS)
    private val fourYou = station("4you", RadioTab.BELARUS)
    private val europa = station("europa", RadioTab.RUSSIA)
    private val stations = listOf(radius, europa, fourYou)

    @Test
    fun customOrderAndHiddenStationsApplyToFavoritesAndCountry() {
        val settings = CatalogOptions(setOf(RadioTab.INTERNATIONAL), emptySet(), emptySet(), false, emptyList(), emptyList())
        val order = settings.move(stations, fourYou, -1)
        val ordered = CatalogOptions(settings.hiddenTabs, setOf(radius.id), emptySet(), false, order, emptyList())
        assertEquals(listOf(fourYou, europa, radius), ordered.ordered(stations))
        assertEquals(RadioTab.BELARUS, ordered.visibleTab(RadioTab.INTERNATIONAL))
        assertEquals(listOf(fourYou), ordered.visibleStations(stations, RadioTab.BELARUS, emptySet()))
        assertEquals(listOf(fourYou, europa), ordered.visibleStations(stations, RadioTab.FAVORITES,
            setOf(radius.id, europa.id, fourYou.id)))
    }

    @Test
    fun unknownStreamOrNetworkFailureCannotHideAStation() {
        assertEquals(Availability.UNKNOWN, Availability.UNAVAILABLE.merge(Availability.UNKNOWN))
        assertEquals(Availability.AVAILABLE, Availability.UNKNOWN.merge(Availability.AVAILABLE))
        listOf(0, 403, 429, 500, 502, 503, 504).forEach { assertFalse(Availability.isDefinitiveHttpFailure(it)) }
        assertTrue(Availability.isDefinitiveHttpFailure(404))
        assertTrue(Availability.isDefinitiveHttpFailure(410))
        for (hide in listOf(true, false)) {
            val options = CatalogOptions(emptySet(), emptySet(), setOf(fourYou.id), hide, emptyList(), emptyList())
            assertEquals(if (hide) listOf(radius) else listOf(radius, fourYou),
                options.visibleStations(stations, RadioTab.BELARUS, emptySet()))
        }
    }

    @Test
    fun radioMetadataSeparatesArtistAndTitle() {
        val track = requireNotNull(Track.from("Artist – Real title - Remix", null, "Station"))
        assertEquals("Artist", track.artist)
        assertEquals("Real title - Remix", track.title)
        assertEquals("Another artist", Track.from("Title", "Another artist", "Station")?.artist)
        assertNull(Track.from("", null, "Station"))
        assertNull(Track.from("station", null, "Station"))
    }

    @Test
    fun internationalStationIsSearchableByCountryInBothLanguages() {
        val station = Station("zet", "Radio ZET", "Pop", "", RadioTab.INTERNATIONAL,
            listOf("https://example.com/radio"), 0, "PL", "Поп", "")
        assertEquals("Poland · Pop", station.displayLabel(Locale.ENGLISH))
        assertEquals("Польша · Поп", station.displayLabel(Locale("ru")))
        assertTrue(station.matches(Station.searchWords("польша поп")))
        assertTrue(station.matches(Station.searchWords("Poland Radio")))
        assertFalse(station.matches(Station.searchWords("Germany")))
    }

    @Test
    fun catalogUpgradePreservesReorderedGroupsAndShowsNewDefaultsElsewhere() {
        val first = station("international-one", RadioTab.INTERNATIONAL)
        val second = station("international-two", RadioTab.INTERNATIONAL)
        val international = station("international-new", RadioTab.INTERNATIONAL)
        val russian = station("maximum", RadioTab.RUSSIA)
        val previous = CatalogOptions(emptySet(), emptySet(), emptySet(), false,
            listOf(fourYou.id, radius.id, europa.id, first.id, second.id), emptyList())
        val upgraded = listOf(radius, fourYou, russian, europa, international, first, second)
        assertEquals(listOf(fourYou, radius, russian, europa, international, first, second), previous.ordered(upgraded))
    }

    @Test
    fun favoriteOrderCrossesRegionsAndKeepsRegionalOrderAndVisibility() {
        val options = CatalogOptions(emptySet(), emptySet(), setOf(fourYou.id), true,
            listOf(radius.id, fourYou.id), listOf(fourYou.id, europa.id))
        val favorites = setOf(radius.id, fourYou.id, europa.id)
        assertEquals(listOf(fourYou, europa, radius), options.stationsForTab(stations, RadioTab.FAVORITES, favorites))
        assertEquals(listOf(europa, radius), options.visibleStations(stations, RadioTab.FAVORITES, favorites))
        assertEquals(listOf(radius, fourYou), options.stationsForTab(stations, RadioTab.BELARUS, favorites))
    }

    private fun station(id: String, group: RadioTab) =
        Station(id, id, "", "", group, listOf("https://example.com/radio"), 0, "", "", "")
}
