package ch.duartesantos.opengym.wear;

import android.content.Context;
import android.net.Uri;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The wire to the phone app, over Play services' Wearable Data Layer. The phone keeps the running
 * workout in one data item (/opengym/session, WatchPlugin.java on the phone); the watch sends what
 * is done on it as messages (/opengym/event), which the phone queues until its page takes them.
 *
 * The same paths and names are in the phone's WatchInbox.java.
 */
final class Link {
    static final String PATH_SESSION = "/opengym/session";
    static final String PATH_EVENT = "/opengym/event";
    static final String CAPABILITY_PHONE = "opengym_phone";
    static final String KEY_JSON = "json";

    interface Done<T> {
        void run(T result);
    }

    interface Fetched {
        /** `ok` false when Play services could not be asked; `json` null when nothing is published. */
        void run(boolean ok, String json);
    }

    private static final long TIMEOUT_S = 10;
    // One at a time, in order: a tick and the rest skipped after it reach the phone in that order.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Link() {}

    /** Sends one event to the phone. `done` gets whether a phone took it, on the worker thread. */
    static void send(Context context, String json, Done<Boolean> done) {
        Context ctx = context.getApplicationContext();
        worker.execute(() -> {
            boolean sent = false;
            try {
                CapabilityInfo info = Tasks.await(
                        Wearable.getCapabilityClient(ctx).getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
                        TIMEOUT_S, TimeUnit.SECONDS);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                for (Node node : info.getNodes()) {
                    Tasks.await(Wearable.getMessageClient(ctx).sendMessage(node.getId(), PATH_EVENT, data), TIMEOUT_S, TimeUnit.SECONDS);
                    sent = true;
                }
            } catch (Exception ignored) {
                // The phone out of reach, or Play services busy: the caller keeps what it sent.
            }
            if (done != null) done.run(sent);
        });
    }

    /** The workout the phone published last. `done` runs on the worker thread. */
    static void fetchSession(Context context, Fetched done) {
        Context ctx = context.getApplicationContext();
        worker.execute(() -> {
            String json = null;
            boolean ok = false;
            try {
                Uri uri = new Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PATH_SESSION).build();
                DataItemBuffer items = Tasks.await(Wearable.getDataClient(ctx).getDataItems(uri), TIMEOUT_S, TimeUnit.SECONDS);
                try {
                    for (DataItem item : items) {
                        String s = jsonOf(item);
                        if (s != null) json = s;
                    }
                } finally {
                    items.release();
                }
                ok = true;
            } catch (Exception ignored) {
                // Play services not ready: what the watch last saw stays on screen.
            }
            done.run(ok, json);
        });
    }

    static String jsonOf(DataItem item) {
        if (item == null || item.getUri() == null || !PATH_SESSION.equals(item.getUri().getPath())) return null;
        return DataMapItem.fromDataItem(item).getDataMap().getString(KEY_JSON);
    }
}
