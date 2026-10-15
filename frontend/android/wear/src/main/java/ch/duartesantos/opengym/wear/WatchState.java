package ch.duartesantos.opengym.wear;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the watch knows: the phone's workout, the ticks the phone has not confirmed yet, and the
 * rest the watch is timing. One per process; the screen (MainActivity), the listener for the
 * phone's updates (WearListener) and the alarm all go through it.
 *
 * The rest follows the phone whenever the phone says something about it. Between a tick on the
 * watch and the phone hearing of it, the watch times the rest that set earns (the snapshot carries
 * it per set), so the buzz comes on time even while the phone sleeps in a pocket.
 */
final class WatchState {
    interface Listener {
        void changed();
    }

    static final String CHANNEL = "workout";
    static final int NOTIFY_STARTED = 1;
    private static final String PREFS = "opengym-wear";
    private static final String KEY_SNAPSHOT = "snapshot";
    // How soon a send that did not reach the phone is tried again.
    private static final long RETRY_MS = 5_000;

    private static WatchState instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Session session;
    private String lastJson;
    private List<Session.Tick> pending = new ArrayList<>();
    // The rest being timed: from the phone's snapshot, or started here by a tick.
    private Session.Rest rest;
    // Ticks and rest changes on their way to the phone, kept until one of its nodes takes them.
    private final Outbox outbox = new Outbox();
    private boolean sending;

    static synchronized WatchState get(Context ctx) {
        if (instance == null) {
            instance = new WatchState();
            // The last workout seen, for a process started again mid-workout. The phone's data item
            // is fetched as well when the screen opens (MainActivity), and wins.
            String saved = prefs(ctx).getString(KEY_SNAPSHOT, null);
            instance.session = Session.parse(saved);
            if (instance.session != null) instance.lastJson = saved;
        }
        return instance;
    }

    synchronized Session session() {
        return session;
    }

    synchronized Session.Rest rest() {
        return rest;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.changed();
        });
    }

    /** The phone published its workout; `json` null means it ended. */
    void onSnapshot(Context ctx, String json) {
        boolean started, ended;
        synchronized (this) {
            if (json != null && json.equals(lastJson)) return;
            Session next = Session.parse(json);
            // Not a snapshot this version understands (a newer phone app): keep what is shown.
            if (json != null && next == null) return;
            started = next != null && (session == null || !session.sid.equals(next.sid));
            ended = next == null && session != null;
            long now = System.currentTimeMillis();
            if (next != null) {
                pending = next.keepPending(pending, now);
                rest = Session.restAfterSnapshot(rest, next.rest, pending.isEmpty(), now);
            } else {
                pending = new ArrayList<>();
                rest = null;
            }
            session = next;
            lastJson = next == null ? null : json;
            prefs(ctx).edit().putString(KEY_SNAPSHOT, lastJson).apply();
            armAlarm(ctx, now);
        }
        // The phone just spoke: it is in reach for whatever is still waiting.
        flush(ctx);
        if (started) notifyStarted(ctx);
        if (ended) {
            ctx.getSystemService(NotificationManager.class).cancel(NOTIFY_STARTED);
            HeartService.stop(ctx);
        }
        changed();
    }

    /** Ticks the next open set of exercise `idx` and tells the phone. False when there was none. */
    boolean tick(Context ctx, int idx) {
        Session.Tick t;
        synchronized (this) {
            if (session == null) return false;
            long now = System.currentTimeMillis();
            t = session.tick(idx, now);
            if (t == null) return false;
            pending.add(t);
            int sec = session.entries.get(t.idx).sets.get(t.i).rest;
            // The last side of a per-side row, or an ordinary row: the rest that set earns.
            boolean rowDone = session.entries.get(t.idx).sets.get(t.i).done;
            if (sec > 0 && rowDone && !session.allDone()) {
                Session.Rest r = new Session.Rest();
                r.total = sec;
                r.endsAt = now + sec * 1000L;
                r.local = true;
                r.tickAt = now;
                rest = r;
            }
            armAlarm(ctx, now);
        }
        // Worth sending for as long as the watch shows it as pending (Session.keepPending).
        send(ctx, t.toJson(), Session.PENDING_MS);
        changed();
        return true;
    }

    void skipRest(Context ctx) {
        synchronized (this) {
            if (rest == null) return;
            rest = null;
            RestAlarm.cancel(ctx);
        }
        send(ctx, event("skip"), Session.FRESH_MS);
        changed();
    }

    void addRest(Context ctx) {
        synchronized (this) {
            if (rest == null) return;
            if (rest.endsAt > 0) rest.endsAt += 15_000;
            else rest.left += 15;
            rest.total += 15;
            armAlarm(ctx, System.currentTimeMillis());
        }
        send(ctx, event("add"), Session.FRESH_MS);
        changed();
    }

    /** The alarm went off: the rest is over here, whatever the phone says next. */
    void restOver(Context ctx) {
        synchronized (this) {
            if (rest != null && rest.endsAt > 0 && rest.endsAt <= System.currentTimeMillis() + 1000) rest = null;
        }
        changed();
    }

    private void send(Context ctx, String json, long keepMs) {
        outbox.add(json, System.currentTimeMillis(), keepMs);
        flush(ctx);
    }

    // Sends what waits in the outbox, one at a time and in order. What the phone does not take is
    // tried again in a moment, until it is taken or no longer worth sending.
    private void flush(Context ctx) {
        String json;
        synchronized (this) {
            if (sending) return;
            json = outbox.peek(System.currentTimeMillis());
            if (json == null) return;
            sending = true;
        }
        Link.send(ctx, json, sent -> {
            synchronized (this) {
                sending = false;
                if (sent) outbox.delivered(json);
            }
            if (sent) flush(ctx);
            else main.postDelayed(() -> flush(ctx), RETRY_MS);
        });
    }

    // Inside the lock.
    private void armAlarm(Context ctx, long now) {
        if (rest != null && rest.endsAt > now) RestAlarm.schedule(ctx, rest.endsAt);
        else RestAlarm.cancel(ctx);
    }

    private static String event(String action) {
        try {
            return new JSONObject().put("type", "rest").put("action", action).put("at", System.currentTimeMillis()).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // A workout started on the phone: one tap opens it here. The watch cannot open its own screen
    // from the background, and heart rate starts once the screen has been opened (HeartService).
    private static void notifyStarted(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(ctx);
        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n = new android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(ctx.getString(R.string.workout_started))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIFY_STARTED, n);
        } catch (SecurityException ignored) {
            // Notifications not allowed: the app is still in the launcher.
        }
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, ctx.getString(R.string.channel_workout), NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
