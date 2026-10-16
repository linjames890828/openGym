package ch.duartesantos.opengym;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import java.nio.charset.StandardCharsets;

/**
 * Takes what the watch sends (a set ticked, the rest skipped, heart-rate samples), even when the
 * app is not running: Play services starts this service for it. The event goes into the inbox and,
 * if the page is up, the page is told to take it (WatchPlugin).
 */
public class WatchListenerService extends WearableListenerService {
    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!WatchInbox.PATH_EVENT.equals(event.getPath())) return;
        byte[] data = event.getData();
        if (data == null) return;
        if (WatchInbox.add(this, new String(data, StandardCharsets.UTF_8))) WatchPlugin.ping();
    }
}
