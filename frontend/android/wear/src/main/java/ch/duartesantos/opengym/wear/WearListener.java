package ch.duartesantos.opengym.wear;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * The phone's workout as it changes. Play services starts this whenever the phone writes or
 * deletes /opengym/session, with the watch app open or not, so the rest buzz and the "workout
 * started" notification do not wait for the screen.
 */
public class WearListener extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer events) {
        // The buffer is Play services' to release once this returns.
        for (DataEvent ev : events) {
            if (ev.getDataItem() == null || ev.getDataItem().getUri() == null) continue;
            if (!Link.PATH_SESSION.equals(ev.getDataItem().getUri().getPath())) continue;
            if (ev.getType() == DataEvent.TYPE_DELETED) WatchState.get(this).onSnapshot(this, null);
            else if (ev.getType() == DataEvent.TYPE_CHANGED) WatchState.get(this).onSnapshot(this, Link.jsonOf(ev.getDataItem()));
        }
    }
}
