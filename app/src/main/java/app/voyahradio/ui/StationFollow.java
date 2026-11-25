package app.voyahradio.ui;

import java.util.List;

/**
 * Decides how the station list follows the station in the player, free of Android types so that it runs in JVM tests.
 *
 * <p>The radio opens on the station, a tab opens on it when the tab has it and at the top otherwise, and a new search
 * starts at the top. When playback moves to another station on the same tab and search, the list brings it into view.
 * A station that is already fully visible leaves the list where it is, and a shown station keeps one row of context
 * above it.
 */
final class StationFollow {
    private StationFollow() {}

    enum Move {
        /** Leave the list where it is. */
        STAY,
        /** Show the top of the list. */
        TOP,
        /** Show the top of a list that was just filled, then bring the station into view unless it is visible there. */
        SHOW_FROM_TOP,
        /** Bring the station into view from where the list is, unless it is fully visible. */
        FOLLOW
    }

    /**
     * How the list moves after a render. {@code opened} is the first render of the screen; the other flags say what
     * changed since the previous render, and {@code row} is the station's row in the new list or -1.
     */
    static Move move(boolean opened, boolean newTab, boolean newQuery, boolean newStation, int row) {
        if (opened || newTab) return row < 0 ? Move.TOP : Move.SHOW_FROM_TOP;
        if (newQuery) return Move.TOP;
        if (newStation && row >= 0) return Move.FOLLOW;
        return Move.STAY;
    }

    /** The row of {@code item} in a list shown {@code columns} per row, or -1 when the list does not have it. */
    static <T> int rowOf(List<T> items, T item, int columns) {
        int index = item == null ? -1 : items.indexOf(item);
        return index < 0 ? -1 : index / Math.max(1, columns);
    }

    /** The row to put at the top so that {@code row} has the row above it in view. */
    static int firstRow(int row) {
        return Math.max(0, row - 1);
    }

    /** Whether a tile from {@code top} to {@code bottom} lies inside a list of {@code height} pixels. */
    static boolean fullyVisible(int top, int bottom, int height) {
        return top >= 0 && bottom <= height;
    }

    /**
     * Whether the list may move on its own: never while a finger is on it or while it scrolls under the user's
     * control or from a fling; a scroll the list started itself may be redirected.
     */
    static boolean mayMove(boolean touching, boolean scrolling, boolean ownScroll) {
        return !touching && (!scrolling || ownScroll);
    }
}
