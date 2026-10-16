package ch.duartesantos.opengym;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the watch sent, kept until the page takes it (WatchPlugin.drain). The watch can tick a set
 * while the WebView is frozen in a pocket; the event waits here, in the app's own preferences so a
 * killed process does not lose it, and the page applies it when it runs again (lib/watch-sync.js).
 *
 * Also the paths and names the phone and the watch agree on; the watch module keeps the same
 * strings in its own copy (wear/.../Link.java).
 */
final class WatchInbox {
    static final String PATH_SESSION = "/opengym/session";
    static final String PATH_EVENT = "/opengym/event";
    static final String CAPABILITY_WEAR = "opengym_wear";
    static final String KEY_JSON = "json";

    private static final String PREFS = "opengym-watch";
    private static final String KEY_INBOX = "inbox";
    // A watch that sends heart-rate batches every half minute fills about 120 in an hour. Far more
    // than that unread means the page has not run for a long time; the oldest go first.
    static final int MAX_EVENTS = 1000;
    // No event the watch sends comes near this; anything larger is not one of ours.
    static final int MAX_EVENT_BYTES = 64 * 1024;

    private WatchInbox() {}

    /** Adds one event. Returns false when it is not a JSON object of a sane size. */
    static synchronized boolean add(Context ctx, String json) {
        if (json == null || json.length() > MAX_EVENT_BYTES) return false;
        JSONObject ev;
        try {
            ev = new JSONObject(json);
        } catch (JSONException e) {
            return false;
        }
        JSONArray inbox = read(ctx);
        JSONArray next = new JSONArray();
        int from = Math.max(0, inbox.length() - (MAX_EVENTS - 1));
        for (int i = from; i < inbox.length(); i++) next.put(inbox.opt(i));
        next.put(ev);
        prefs(ctx).edit().putString(KEY_INBOX, next.toString()).apply();
        return true;
    }

    /** Everything waiting, oldest first, and the inbox emptied. */
    static synchronized JSONArray drain(Context ctx) {
        JSONArray inbox = read(ctx);
        if (inbox.length() > 0) prefs(ctx).edit().remove(KEY_INBOX).apply();
        return inbox;
    }

    private static JSONArray read(Context ctx) {
        String s = prefs(ctx).getString(KEY_INBOX, null);
        if (s == null) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
