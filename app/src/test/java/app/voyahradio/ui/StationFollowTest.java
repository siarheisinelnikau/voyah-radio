package app.voyahradio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class StationFollowTest {
    @Test
    public void opensOnTheStationOrAtTheTop() {
        assertEquals(StationFollow.Move.SHOW_FROM_TOP, StationFollow.move(true, false, false, false, 7));
        assertEquals(StationFollow.Move.TOP, StationFollow.move(true, false, false, false, -1));
    }

    @Test
    public void aTabOpensOnTheStationWhenItHasIt() {
        assertEquals(StationFollow.Move.SHOW_FROM_TOP, StationFollow.move(false, true, false, false, 3));
        assertEquals(StationFollow.Move.TOP, StationFollow.move(false, true, false, true, -1));
        // A tab change outweighs a new search typed into the same render.
        assertEquals(StationFollow.Move.SHOW_FROM_TOP, StationFollow.move(false, true, true, false, 0));
    }

    @Test
    public void aNewSearchStartsAtTheTop() {
        assertEquals(StationFollow.Move.TOP, StationFollow.move(false, false, true, false, 4));
        assertEquals(StationFollow.Move.TOP, StationFollow.move(false, false, true, true, 4));
    }

    @Test
    public void aNewStationIsFollowedOnlyWhenTheListHasIt() {
        assertEquals(StationFollow.Move.FOLLOW, StationFollow.move(false, false, false, true, 12));
        assertEquals(StationFollow.Move.STAY, StationFollow.move(false, false, false, true, -1));
        assertEquals(StationFollow.Move.STAY, StationFollow.move(false, false, false, false, 12));
    }

    @Test
    public void rowsCountStationsPerColumn() {
        List<String> stations = Arrays.asList("a", "b", "c", "d", "e");
        assertEquals(0, StationFollow.rowOf(stations, "a", 2));
        assertEquals(0, StationFollow.rowOf(stations, "b", 2));
        assertEquals(2, StationFollow.rowOf(stations, "e", 2));
        assertEquals(4, StationFollow.rowOf(stations, "e", 1));
        assertEquals(-1, StationFollow.rowOf(stations, "z", 2));
        assertEquals(-1, StationFollow.rowOf(stations, null, 2));
        assertEquals(-1, StationFollow.rowOf(Collections.emptyList(), "a", 2));
    }

    @Test
    public void aShownStationKeepsTheRowAboveInView() {
        assertEquals(0, StationFollow.firstRow(0));
        assertEquals(0, StationFollow.firstRow(1));
        assertEquals(5, StationFollow.firstRow(6));
    }

    @Test
    public void onlyATileInsideTheListIsFullyVisible() {
        assertTrue(StationFollow.fullyVisible(0, 102, 400));
        assertTrue(StationFollow.fullyVisible(298, 400, 400));
        assertFalse(StationFollow.fullyVisible(-1, 101, 400));
        assertFalse(StationFollow.fullyVisible(300, 401, 400));
    }

    @Test
    public void theListNeverMovesUnderTheUser() {
        assertTrue(StationFollow.mayMove(false, false, false));
        assertFalse(StationFollow.mayMove(true, false, false));
        assertFalse(StationFollow.mayMove(false, true, false));
        assertTrue(StationFollow.mayMove(false, true, true));
        assertFalse(StationFollow.mayMove(true, true, true));
    }
}
