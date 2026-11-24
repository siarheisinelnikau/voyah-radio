package app.voyahradio.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Receives the steering wheel's media keys on Voyah head units.
 *
 * <p>On these head units the wheel keys never reach Android media sessions. The firmware key service
 * ({@code com.qinggan.keymanager.service}) sends next, previous and play/pause to the app that took audio focus for
 * media most recently, but only when that app has a key listener registered with it; otherwise they go to the
 * firmware media centre, which applies them to Bluetooth or USB music. This class registers that listener through
 * the firmware's own client, {@code com.qinggan.system.KeyManager} in {@code /system/framework/QGAPI.jar}, so the
 * wheel controls this app while it plays, exactly like the firmware's own players. On a device without that client
 * it does nothing.
 */
final class OemMediaKeys {
    /** Key codes of the firmware's {@code QGKeyEvent}. */
    static final int NEXT = 3;
    static final int PREVIOUS = 4;
    static final int PLAY_PAUSE = 6;
    static final int PLAY = 8;
    static final int PAUSE = 9;

    private static final int[] KEYS = {NEXT, PREVIOUS, PLAY_PAUSE, PLAY, PAUSE};
    private static final String TAG = "OemMediaKeys";
    private static final String MANAGER = "com.qinggan.system.KeyManager";
    private static final long RETRY_MS = 50;

    interface Receiver {
        /** Runs one of the key codes above on the main thread after a short press. */
        void onMediaKey(int key);
    }

    private final Receiver receiver;
    private final Class<?> managerClass;
    private final Class<?> listenerClass;
    private final Object listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Object manager;
    private volatile boolean closed;

    private OemMediaKeys(Receiver receiver, Class<?> managerClass, Class<?> listenerClass) {
        this.receiver = receiver;
        this.managerClass = managerClass;
        this.listenerClass = listenerClass;
        listener = proxy(listenerClass, this::onKeyEvent);
    }

    /** Connects to the firmware key service, or returns null when this head unit has none. Call on the main thread. */
    static OemMediaKeys attach(Context context, Receiver receiver) {
        try {
            Class<?> managerClass = Class.forName(MANAGER);
            Class<?> listenerClass = Class.forName(MANAGER + "$OnKeyEventListener");
            Class<?> initClass = Class.forName(MANAGER + "$OnInitListener");
            OemMediaKeys keys = new OemMediaKeys(receiver, managerClass, listenerClass);
            // The client reports every connection, including a new one after the key service restarted, and a new
            // connection has no listeners yet.
            Object onInit = proxy(initClass, (proxy, method, args) -> {
                if ("onResult".equals(method.getName()) && args != null && Boolean.TRUE.equals(args[0])) {
                    keys.main.post(keys::register);
                }
                return null;
            });
            keys.manager = managerClass.getMethod("getInstance", Context.class, initClass)
                    .invoke(null, context.getApplicationContext(), onInit);
            return keys;
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w(TAG, "The firmware key service is not available", error);
            return null;
        }
    }

    /** Stops receiving the keys, which then go to the firmware media centre again. */
    void detach() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        Object instance = manager;
        if (instance == null) return;
        try {
            managerClass.getMethod("unregisterKeyEventListener", listenerClass).invoke(instance, listener);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Unable to release the steering wheel media keys", error);
        }
    }

    private void register() {
        if (closed) return;
        Object instance = manager;
        if (instance == null) {
            main.postDelayed(this::register, RETRY_MS);
            return;
        }
        try {
            Object status = managerClass.getMethod("registerKeyEventListener", listenerClass, int[].class)
                    .invoke(instance, listener, KEYS.clone());
            Log.i(TAG, "Listening to the steering wheel media keys, status " + status);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Unable to listen to the steering wheel media keys", error);
        }
    }

    private Object onKeyEvent(Object proxy, Method method, Object[] args) {
        // The key service calls on a binder thread and does not wait for the answer to an ordinary listener.
        if ("onKeyShortClick".equals(method.getName())) {
            int key = (Integer) args[0];
            main.post(() -> {
                if (!closed) receiver.onMediaKey(key);
            });
            return true;
        }
        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(OemMediaKeys.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> method.getDeclaringClass() == Object.class
                        ? objectMethod(proxy, method, args) : handler.invoke(proxy, method, args));
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return TAG;
            default:
                return null;
        }
    }
}
