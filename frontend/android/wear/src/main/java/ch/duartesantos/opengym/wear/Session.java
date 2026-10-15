package ch.duartesantos.opengym.wear;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The phone's running workout as the watch sees it: the snapshot lib/watch-snapshot.js builds,
 * parsed. Plain Java and org.json only, so the JVM tests can run it (SessionTest).
 *
 * A tick made on the watch is put on the session at once (tick) and kept as pending until a
 * snapshot from the phone shows it: the phone's page can be frozen in a pocket, and a snapshot it
 * published before it saw the tick must not take the tick back off the screen.
 */
final class Session {
    static final int VERSION = 1;
    // A tick the phone has not confirmed after this long is given up on: the phone dropped it,
    // or the set was unticked there.
    static final long PENDING_MS = 120_000;

    static final class SetRow {
        boolean done;
        boolean warm;
        double w = Double.NaN, r = Double.NaN, sec = Double.NaN, km = Double.NaN, min = Double.NaN;
        // A per-side row: each side's tick. Both null for an ordinary row.
        Boolean left, right;
        int rest;

        boolean perSide() {
            return left != null && right != null;
        }

        /** The side the next tick goes to on a per-side row, or null when there is none to tick. */
        String openSide() {
            if (!perSide()) return null;
            if (!left) return "L";
            if (!right) return "R";
            return null;
        }
    }

    static final class Entry {
        String name;
        String mode;
        final List<SetRow> sets = new ArrayList<>();

        /** The first set not yet done, or -1 when the exercise is finished. */
        int firstOpen() {
            for (int i = 0; i < sets.size(); i++) if (!sets.get(i).done) return i;
            return -1;
        }
    }

    static final class Rest {
        long endsAt;   // 0 while paused
        int total;
        int left;      // seconds, while paused
    }

    /** A tick sent to the phone and not yet seen in a snapshot from it. */
    static final class Tick {
        final String sid;
        final int idx, i;
        final String side;
        final long at;

        Tick(String sid, int idx, int i, String side, long at) {
            this.sid = sid;
            this.idx = idx;
            this.i = i;
            this.side = side;
            this.at = at;
        }

        String toJson() {
            try {
                JSONObject o = new JSONObject()
                        .put("type", "set").put("sid", sid).put("idx", idx).put("i", i)
                        .put("done", true).put("at", at);
                if (side != null) o.put("side", side);
                return o.toString();
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    String sid;
    String name;
    String unit;
    long start;
    int cur;
    Rest rest;
    final List<Entry> entries = new ArrayList<>();

    /** The snapshot, or null when it is not one this watch app understands. */
    static Session parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("v", 0) != VERSION) return null;
            Session s = new Session();
            s.sid = o.getString("sid");
            s.name = o.optString("name", "");
            s.unit = "lb".equals(o.optString("unit")) ? "lb" : "kg";
            s.start = o.optLong("start", 0);
            JSONObject rest = o.optJSONObject("rest");
            if (rest != null) {
                Rest r = new Rest();
                r.endsAt = rest.isNull("endsAt") ? 0 : rest.optLong("endsAt", 0);
                r.total = rest.optInt("total", 0);
                r.left = rest.optInt("left", 0);
                if (r.total > 0) s.rest = r;
            }
            JSONArray entries = o.optJSONArray("entries");
            for (int k = 0; entries != null && k < entries.length(); k++) {
                JSONObject e = entries.getJSONObject(k);
                Entry entry = new Entry();
                entry.name = e.optString("name", "");
                entry.mode = e.optString("mode", "reps");
                JSONArray sets = e.optJSONArray("sets");
                for (int i = 0; sets != null && i < sets.length(); i++) entry.sets.add(row(sets.getJSONObject(i)));
                s.entries.add(entry);
            }
            s.cur = Math.max(0, Math.min(s.entries.size() - 1, o.optInt("cur", 0)));
            return s;
        } catch (JSONException e) {
            return null;
        }
    }

    private static SetRow row(JSONObject o) {
        SetRow r = new SetRow();
        r.done = o.optBoolean("done", false);
        r.warm = o.optBoolean("warm", false);
        r.w = o.optDouble("w", Double.NaN);
        r.r = o.optDouble("r", Double.NaN);
        r.sec = o.optDouble("sec", Double.NaN);
        r.km = o.optDouble("km", Double.NaN);
        r.min = o.optDouble("min", Double.NaN);
        r.rest = Math.max(0, o.optInt("rest", 0));
        JSONObject sides = o.optJSONObject("sides");
        if (sides != null) {
            r.left = sides.optBoolean("L", false);
            r.right = sides.optBoolean("R", false);
        }
        return r;
    }

    /** The exercise after `from` with something left to do, wrapping round; -1 when all is done. */
    int nextOpenEntry(int from) {
        int n = entries.size();
        for (int step = 1; step <= n; step++) {
            int k = ((from + step) % n + n) % n;
            if (entries.get(k).firstOpen() >= 0) return k;
        }
        return -1;
    }

    boolean allDone() {
        for (Entry e : entries) if (e.firstOpen() >= 0) return false;
        return !entries.isEmpty();
    }

    /**
     * Ticks the next open set of exercise `idx` here, as the phone will once it hears of it.
     * Returns the tick to send, or null when the exercise has nothing left.
     */
    Tick tick(int idx, long now) {
        if (idx < 0 || idx >= entries.size()) return null;
        Entry e = entries.get(idx);
        int i = e.firstOpen();
        if (i < 0) return null;
        SetRow row = e.sets.get(i);
        String side = row.openSide();
        apply(row, side);
        return new Tick(sid, idx, i, side, now);
    }

    private static void apply(SetRow row, String side) {
        if (side == null) {
            row.done = true;
            return;
        }
        if ("L".equals(side)) row.left = true;
        else row.right = true;
        row.done = row.left && row.right;
    }

    /**
     * Puts back the ticks this snapshot does not show yet, and returns the ones still pending.
     * A tick for another session, a set that is gone, or one older than PENDING_MS is dropped.
     */
    List<Tick> keepPending(List<Tick> pending, long now) {
        if (pending == null || pending.isEmpty()) return Collections.emptyList();
        List<Tick> still = new ArrayList<>();
        for (Tick t : pending) {
            if (!t.sid.equals(sid) || now - t.at > PENDING_MS) continue;
            if (t.idx >= entries.size() || t.i >= entries.get(t.idx).sets.size()) continue;
            SetRow row = entries.get(t.idx).sets.get(t.i);
            boolean shown = t.side == null ? row.done
                    : row.perSide() && ("L".equals(t.side) ? row.left : row.right);
            if (shown) continue;
            if (t.side != null && !row.perSide()) continue;
            apply(row, t.side);
            still.add(t);
        }
        return still;
    }

    /** "80 kg × 5", "45 s", "5 km · 30 min": what the set asks for, in the profile's unit. */
    static String describe(SetRow row, String mode, String unit) {
        List<String> parts = new ArrayList<>();
        if ("cardio".equals(mode)) {
            if (!Double.isNaN(row.km)) parts.add(num(row.km) + " km");
            if (!Double.isNaN(row.min)) parts.add(num(row.min) + " min");
            return String.join(" · ", parts);
        }
        if ("time".equals(mode)) {
            if (!Double.isNaN(row.sec)) parts.add(num(row.sec) + " s");
            if (!Double.isNaN(row.w) && row.w > 0) parts.add(num(row.w) + " " + unit);
            return String.join(" · ", parts);
        }
        boolean hasW = !Double.isNaN(row.w) && row.w > 0;
        boolean hasR = !Double.isNaN(row.r);
        if (hasW && hasR) return num(row.w) + " " + unit + " × " + num(row.r);
        if (hasR) return "× " + num(row.r);
        return hasW ? num(row.w) + " " + unit : "";
    }

    /** 80, 82.5, 1.25 — no trailing zeros, a point in every locale. */
    static String num(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** m:ss, never negative. */
    static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
