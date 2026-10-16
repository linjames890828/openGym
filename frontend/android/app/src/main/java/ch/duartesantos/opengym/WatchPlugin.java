package ch.duartesantos.opengym;

import android.content.Context;
import android.net.Uri;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The phone's half of the Wear OS watch app (docs/MOBILE.md "Wear OS watch"). The page decides
 * what the watch shows (lib/watch-snapshot.js); this side only carries it over the Wearable Data
 * Layer, and hands back what the watch sent (WatchListenerService, WatchInbox).
 *
 * The running workout is one data item at /opengym/session. A data item is kept by Play services
 * and delivered whenever the watch is in reach, so a watch that comes back in range, or an app
 * opened on it later, finds the workout as it is now. It is deleted when the workout ends.
 *
 * Nothing is written unless a watch with openGym on it is paired (the capability the watch app
 * declares): a phone without a watch, or without Play services, keeps everything to itself.
 *
 * Usage from JS (lib/watch-sync.js):
 *   const Watch = registerPlugin('Watch');
 *   await Watch.publish({ json });   // { sent }
 *   await Watch.clear();
 *   await Watch.drain();             // { events: [...] }, oldest first
 *   Watch.addListener('watch', () => drain());   // something arrived
 */
@CapacitorPlugin(name = "Watch")
public class WatchPlugin extends Plugin {
    private static volatile WatchPlugin instance;
    // How long "is there a watch" is believed. Asking Play services on every publish would cost an
    // IPC per set ticked; a watch paired mid-workout is seen within this.
    private static final long WATCH_CHECK_MS = 60_000;
    private static final long TIMEOUT_S = 10;

    // One at a time, in order: a clear queued behind a publish must not overtake it.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private long checkedAt = 0;
    private boolean hasWatch = false;

    @Override
    public void load() {
        instance = this;
        super.load();
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
        worker.shutdown();
        super.handleOnDestroy();
    }

    /** Tells the page there is something in the inbox. A no-op while the page is not there. */
    static void ping() {
        WatchPlugin p = instance;
        if (p != null) p.notifyListeners("watch", new JSObject());
    }

    @PluginMethod
    public void publish(PluginCall call) {
        String json = call.getString("json");
        if (json == null || json.length() > 90_000) {
            call.reject("json missing or too large");
            return;
        }
        Context ctx = getContext().getApplicationContext();
        worker.execute(() -> {
            JSObject out = new JSObject();
            try {
                if (!watchPaired(ctx)) {
                    out.put("sent", false);
                    call.resolve(out);
                    return;
                }
                PutDataMapRequest req = PutDataMapRequest.create(WatchInbox.PATH_SESSION);
                req.getDataMap().putString(WatchInbox.KEY_JSON, json);
                PutDataRequest put = req.asPutDataRequest().setUrgent();
                Tasks.await(Wearable.getDataClient(ctx).putDataItem(put), TIMEOUT_S, TimeUnit.SECONDS);
                out.put("sent", true);
                call.resolve(out);
            } catch (Exception e) {
                // No Play services, no Wear OS app on the phone, the watch out of reach: nothing to do.
                out.put("sent", false);
                call.resolve(out);
            }
        });
    }

    @PluginMethod
    public void clear(PluginCall call) {
        Context ctx = getContext().getApplicationContext();
        worker.execute(() -> {
            try {
                Uri uri = new Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchInbox.PATH_SESSION).build();
                Tasks.await(Wearable.getDataClient(ctx).deleteDataItems(uri, DataClient.FILTER_LITERAL), TIMEOUT_S, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Nothing was ever published, or Play services is not there.
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void drain(PluginCall call) {
        JSONArray events = WatchInbox.drain(getContext());
        JSArray out = new JSArray();
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev != null) out.put(ev);
        }
        JSObject res = new JSObject();
        res.put("events", out);
        call.resolve(res);
    }

    // On the worker thread only.
    private boolean watchPaired(Context ctx) throws Exception {
        long now = System.currentTimeMillis();
        if (now - checkedAt < WATCH_CHECK_MS) return hasWatch;
        CapabilityInfo info = Tasks.await(
                Wearable.getCapabilityClient(ctx).getCapability(WatchInbox.CAPABILITY_WEAR, CapabilityClient.FILTER_ALL),
                TIMEOUT_S, TimeUnit.SECONDS);
        hasWatch = info != null && !info.getNodes().isEmpty();
        checkedAt = now;
        return hasWatch;
    }
}
