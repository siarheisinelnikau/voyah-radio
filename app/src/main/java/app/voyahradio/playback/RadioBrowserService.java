package app.voyahradio.playback;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.service.media.MediaBrowserService;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands the radio's media session to a media widget, such as the head unit's home screen media card (the launcher,
 * which Open Voyah Controls extends with a Voyah Radio source). Only system apps may connect: they control playback
 * through the session and may start it while the player is closed (see {@link #isClient}).
 */
public final class RadioBrowserService extends MediaBrowserService {
    private static final String ROOT = "voyah_radio";
    private static final Set<String> CLIENTS = ConcurrentHashMap.newKeySet();
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            // The radio service lives as long as this binding, so its session token stays valid.
            if (getSessionToken() == null) setSessionToken(((RadioService.LocalBinder) binder).service().sessionToken());
        }

        @Override public void onServiceDisconnected(ComponentName name) {}
    };

    /** Whether this caller connected as a media browser; such a widget may start playback in the background. */
    static boolean isClient(String packageName, int uid) {
        return packageName != null && CLIENTS.contains(key(packageName, uid));
    }

    private static String key(String packageName, int uid) {
        return uid + "/" + packageName;
    }

    @Override public void onCreate() {
        super.onCreate();
        bound = bindService(new Intent(this, RadioService.class), connection, BIND_AUTO_CREATE);
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        if (clientUid != Process.SYSTEM_UID) return null;
        CLIENTS.add(key(clientPackageName, clientUid));
        return new BrowserRoot(ROOT, null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        // The widget only needs the session; stations are chosen in the player.
        result.sendResult(Collections.emptyList());
    }

    @Override public void onDestroy() {
        if (bound) unbindService(connection);
        bound = false;
        super.onDestroy();
    }
}
