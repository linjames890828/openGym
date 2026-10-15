package ch.duartesantos.opengym.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OutboxTest {
    @Test
    public void keepsAnEventUntilThePhoneTakesIt() {
        Outbox box = new Outbox();
        box.add("tick", 0, Session.PENDING_MS);
        // Not delivered: still there for the next try, as often as it takes.
        assertEquals("tick", box.peek(5_000));
        assertEquals("tick", box.peek(10_000));
        box.delivered("tick");
        assertNull(box.peek(10_000));
    }

    @Test
    public void sendsInTheOrderTheyWereMade() {
        Outbox box = new Outbox();
        box.add("tick", 0, Session.PENDING_MS);
        box.add("skip", 1, Session.FRESH_MS);
        assertEquals("tick", box.peek(2));
        // Something other than the head reaching the phone does not take the head off.
        box.delivered("skip");
        assertEquals("tick", box.peek(2));
        box.delivered("tick");
        assertEquals("skip", box.peek(2));
    }

    @Test
    public void dropsWhatIsNoLongerWorthSending() {
        Outbox box = new Outbox();
        box.add("skip", 0, Session.FRESH_MS);
        box.add("tick", 0, Session.PENDING_MS);
        // A skip the phone would ignore by now goes; the tick behind it is still sent.
        assertEquals("tick", box.peek(Session.FRESH_MS + 1));
        assertNull(box.peek(Session.PENDING_MS + 1));
        assertEquals(0, box.size());
    }

    @Test
    public void staysBounded() {
        Outbox box = new Outbox();
        for (int i = 0; i < Outbox.MAX + 10; i++) box.add("e" + i, 0, Session.PENDING_MS);
        assertEquals(Outbox.MAX, box.size());
        assertEquals("e10", box.peek(1));
    }
}
