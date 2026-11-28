package app.voyahradio.ui;

import android.content.Context;
import android.content.res.Configuration;

import app.voyahradio.BuildConfig;

final class ScreenScale {
    private ScreenScale() {}

    static Context apply(Context context) {
        if (!BuildConfig.MAZDA_EZ6) return context;

        Configuration current = context.getResources().getConfiguration();
        // Leave enough room for the player and its controls when the head unit gives us a smaller window.
        float maxWidthScale = current.screenWidthDp / 360f;
        float maxHeightScale = current.screenHeightDp / 400f;
        float scale = Math.min(2f, Math.min(maxWidthScale, maxHeightScale));
        int density = Math.round(current.densityDpi * scale);
        if (current.densityDpi <= 0 || density <= current.densityDpi) return context;

        // Keep the layout breakpoints in the same units as the enlarged views and text.
        float applied = (float) density / current.densityDpi;
        Configuration override = new Configuration();
        override.densityDpi = density;
        override.fontScale = current.fontScale;
        override.screenWidthDp = Math.round(current.screenWidthDp / applied);
        override.screenHeightDp = Math.round(current.screenHeightDp / applied);
        override.smallestScreenWidthDp = Math.round(current.smallestScreenWidthDp / applied);
        return context.createConfigurationContext(override);
    }
}
