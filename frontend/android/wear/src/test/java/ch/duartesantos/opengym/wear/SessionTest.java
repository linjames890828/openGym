package ch.duartesantos.opengym.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SessionTest {
    // The shape lib/watch-snapshot.js builds (its tests pin the phone's side).
    private static final String SNAP = "{\"v\":1,\"sid\":\"s1\",\"name\":\"Push\",\"unit\":\"kg\",\"start\":1000,\"cur\":0,"
            + "\"rest\":null,\"entries\":["
            + "{\"name\":\"Bench press\",\"mode\":\"reps\",\"sets\":["
            + "{\"done\":true,\"w\":40,\"r\":5,\"warm\":true,\"rest\":60},"
            + "{\"done\":false,\"w\":82.5,\"r\":5,\"rest\":120}]},"
            + "{\"name\":\"Side plank\",\"mode\":\"time\",\"sets\":["
            + "{\"done\":false,\"sec\":45,\"sides\":{\"L\":false,\"R\":false},\"rest\":90}]},"
            + "{\"name\":\"Treadmill\",\"mode\":\"cardio\",\"sets\":[{\"done\":false,\"km\":5,\"min\":30}]}]}";

    @Test
    public void parsesTheSnapshot() {
        Session s = Session.parse(SNAP);
        assertNotNull(s);
        assertEquals("s1", s.sid);
        assertEquals("kg", s.unit);
        assertNull(s.rest);
        assertEquals(3, s.entries.size());
        assertEquals(1, s.entries.get(0).firstOpen());
        assertTrue(s.entries.get(0).sets.get(0).warm);
        assertEquals(120, s.entries.get(0).sets.get(1).rest);
        assertTrue(s.entries.get(1).sets.get(0).perSide());
    }

    @Test
    public void refusesWhatItDoesNotUnderstand() {
        assertNull(Session.parse(null));
        assertNull(Session.parse("not json"));
        assertNull(Session.parse("{\"v\":2,\"sid\":\"s1\"}"));
        assertNull(Session.parse("{\"v\":1}"));
    }

    @Test
    public void readsTheRest() {
        Session running = Session.parse(SNAP.replace("\"rest\":null", "\"rest\":{\"endsAt\":5000,\"total\":90}"));
        assertEquals(5000, running.rest.endsAt);
        assertEquals(90, running.rest.total);
        Session paused = Session.parse(SNAP.replace("\"rest\":null", "\"rest\":{\"endsAt\":null,\"total\":90,\"left\":42}"));
        assertEquals(0, paused.rest.endsAt);
        assertEquals(42, paused.rest.left);
    }

    @Test
    public void describesASet() {
        Session s = Session.parse(SNAP);
        assertEquals("82.5 kg × 5", Session.describe(s.entries.get(0).sets.get(1), "reps", s.unit));
        assertEquals("45 s", Session.describe(s.entries.get(1).sets.get(0), "time", s.unit));
        assertEquals("5 km · 30 min", Session.describe(s.entries.get(2).sets.get(0), "cardio", s.unit));
        Session.SetRow bodyweight = new Session.SetRow();
        bodyweight.r = 12;
        assertEquals("× 12", Session.describe(bodyweight, "reps", "kg"));
    }

    @Test
    public void ticksTheNextOpenSetAndSidesOneAtATime() {
        Session s = Session.parse(SNAP);
        Session.Tick t = s.tick(0, 7000);
        assertEquals(0, t.idx);
        assertEquals(1, t.i);
        assertNull(t.side);
        assertTrue(s.entries.get(0).sets.get(1).done);
        assertNull(s.tick(0, 7001));

        Session.Tick left = s.tick(1, 8000);
        assertEquals("L", left.side);
        assertFalse(s.entries.get(1).sets.get(0).done);
        Session.Tick right = s.tick(1, 8001);
        assertEquals("R", right.side);
        assertTrue(s.entries.get(1).sets.get(0).done);
        assertTrue(t.toJson().contains("\"type\":\"set\""));
        assertTrue(left.toJson().contains("\"side\":\"L\""));
    }

    @Test
    public void keepsATickTheSnapshotDoesNotShowYet() {
        Session watch = Session.parse(SNAP);
        List<Session.Tick> pending = new ArrayList<>();
        pending.add(watch.tick(0, 7000));

        // A snapshot the phone published before it heard of the tick: the tick goes back on.
        Session stale = Session.parse(SNAP);
        List<Session.Tick> still = stale.keepPending(pending, 8000);
        assertEquals(1, still.size());
        assertTrue(stale.entries.get(0).sets.get(1).done);

        // The phone has it now: nothing is pending.
        Session fresh = Session.parse(SNAP.replace("{\"done\":false,\"w\":82.5", "{\"done\":true,\"w\":82.5"));
        assertTrue(fresh.keepPending(still, 9000).isEmpty());

        // Given up on after a while, and never carried to another workout.
        assertTrue(Session.parse(SNAP).keepPending(pending, 7000 + Session.PENDING_MS + 1).isEmpty());
        assertTrue(Session.parse(SNAP.replace("\"sid\":\"s1\"", "\"sid\":\"s2\"")).keepPending(pending, 8000).isEmpty());
    }

    private static Session.Rest localRest(long tickAt, int sec) {
        Session.Rest r = new Session.Rest();
        r.total = sec;
        r.endsAt = tickAt + sec * 1000L;
        r.local = true;
        r.tickAt = tickAt;
        return r;
    }

    @Test
    public void followsThePhonesRestWheneverItHasOne() {
        Session.Rest phone = new Session.Rest();
        phone.total = 90;
        phone.endsAt = 100_000;
        assertSame(phone, Session.restAfterSnapshot(localRest(0, 90), phone, true, 5_000));
        assertSame(phone, Session.restAfterSnapshot(null, phone, false, 5_000));
    }

    @Test
    public void keepsTheWatchRestWhileATickIsOnItsWay() {
        Session.Rest mine = localRest(0, 90);
        assertSame(mine, Session.restAfterSnapshot(mine, null, false, 5_000));
    }

    @Test
    public void keepsTheWatchRestWhenThePhoneHeardOfTheTickTooLate() {
        // Ticked at 0 with the phone asleep; the phone woke at 30 s, logged the set without a
        // rest and published none. 60 s of the 90 are still to go here.
        Session.Rest mine = localRest(0, 90);
        assertSame(mine, Session.restAfterSnapshot(mine, null, true, 30_000));
        // And stays through the phone's later snapshots until it runs out.
        assertSame(mine, Session.restAfterSnapshot(mine, null, true, 80_000));
        assertNull(Session.restAfterSnapshot(mine, null, true, 90_000));
    }

    @Test
    public void dropsTheRestWhenThePhoneHeardInTimeAndTimesNone() {
        // Heard within FRESH_MS: the phone decided there is no rest (it ran its own toggle).
        assertNull(Session.restAfterSnapshot(localRest(0, 90), null, true, 3_000));
        // The phone's own rest, ended or skipped there.
        Session.Rest phones = localRest(0, 90);
        phones.local = false;
        assertNull(Session.restAfterSnapshot(phones, null, true, 30_000));
        assertNull(Session.restAfterSnapshot(null, null, true, 30_000));
    }

    @Test
    public void findsTheNextExerciseWithWorkLeft() {
        Session s = Session.parse(SNAP);
        assertEquals(1, s.nextOpenEntry(0));
        assertEquals(0, s.nextOpenEntry(2));
        assertFalse(s.allDone());
    }

    @Test
    public void formatsNumbersAndTheClock() {
        assertEquals("80", Session.num(80));
        assertEquals("82.5", Session.num(82.5));
        assertEquals("1.25", Session.num(1.25));
        assertEquals("1:30", Session.clock(90_000));
        assertEquals("0:01", Session.clock(1));
        assertEquals("0:00", Session.clock(-5000));
    }
}
