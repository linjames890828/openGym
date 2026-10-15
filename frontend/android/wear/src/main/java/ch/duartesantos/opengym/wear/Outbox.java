package ch.duartesantos.opengym.wear;

import java.util.ArrayDeque;

/**
 * What the watch has for the phone and the phone has not taken yet, oldest first: a tick made
 * while the phone was out of reach goes when it is back, in the order it was made (WatchState).
 * Each event is kept only for as long as it is worth sending. Plain Java, so the JVM tests can run
 * it (OutboxTest).
 */
final class Outbox {
    // Far more than a workout makes while the phone is away; past it the oldest go first.
    static final int MAX = 200;

    private static final class Item {
        final String json;
        final long until;

        Item(String json, long until) {
            this.json = json;
            this.until = until;
        }
    }

    private final ArrayDeque<Item> items = new ArrayDeque<>();

    /** Adds one event, worth sending until `now + keepMs`. */
    synchronized void add(String json, long now, long keepMs) {
        items.addLast(new Item(json, now + keepMs));
        while (items.size() > MAX) items.removeFirst();
    }

    /** The oldest event still worth sending, or null; the ones past their time are dropped. */
    synchronized String peek(long now) {
        while (!items.isEmpty() && items.peekFirst().until < now) items.removeFirst();
        Item first = items.peekFirst();
        return first == null ? null : first.json;
    }

    /** `json`, the one peek gave, reached the phone. */
    synchronized void delivered(String json) {
        Item first = items.peekFirst();
        if (first != null && first.json.equals(json)) items.removeFirst();
    }

    synchronized int size() {
        return items.size();
    }
}
