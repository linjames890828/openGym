package ch.duartesantos.opengym.wear;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The watch's one screen: the phone's workout, a set at a time. Done ticks the set the screen
 * shows, ‹ › (or the bezel, or the crown) move between exercises, and a rest takes over the screen
 * with its clock, Skip and +15 s, as on the phone.
 */
public class MainActivity extends Activity implements WatchState.Listener {
    private static final int REQUEST_HEART = 1;
    private static final String PREFS = "opengym-wear-ui";
    private static final String KEY_ASKED = "askedHeart";
    private static final long TICK_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WatchState state;
    private View empty, setView, restView;
    private TextView heart, exercise, setLabel, setValue, restHeart, restClock, restNext;
    private Button done;
    // The exercise on screen. It follows the phone's current one whenever that moves, and the
    // buttons move it here in between.
    private int view = 0;
    private String viewFor = null;
    private int lastCur = -1;
    private float scrolled = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        state = WatchState.get(this);
        empty = findViewById(R.id.empty);
        setView = findViewById(R.id.set_view);
        restView = findViewById(R.id.rest_view);
        heart = findViewById(R.id.heart);
        exercise = findViewById(R.id.exercise);
        setLabel = findViewById(R.id.set_label);
        setValue = findViewById(R.id.set_value);
        restHeart = findViewById(R.id.rest_heart);
        restClock = findViewById(R.id.rest_clock);
        restNext = findViewById(R.id.rest_next);
        done = findViewById(R.id.done);
        done.setOnClickListener(v -> onDone());
        findViewById(R.id.prev).setOnClickListener(v -> move(-1));
        findViewById(R.id.next).setOnClickListener(v -> move(1));
        findViewById(R.id.rest_skip).setOnClickListener(v -> state.skipRest(this));
        findViewById(R.id.rest_add).setOnClickListener(v -> state.addRest(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        state.addListener(this);
        // The data item is the truth: a notification missed while the app was not installed yet,
        // or a workout that ended while this process was gone, is put right here.
        Link.fetchSession(this, (ok, json) -> {
            if (ok) state.onSnapshot(getApplicationContext(), json);
        });
        render();
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        state.removeListener(this);
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    public void changed() {
        render();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderClock();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void onDone() {
        Session s = state.session();
        if (s == null) return;
        if (!state.tick(this, view)) return;
        // The exercise is finished: on to the next one with work left, as the phone does.
        Session after = state.session();
        if (after != null && view < after.entries.size() && after.entries.get(view).firstOpen() < 0) {
            int next = after.nextOpenEntry(view);
            if (next >= 0) view = next;
        }
        render();
    }

    private void move(int by) {
        Session s = state.session();
        if (s == null || s.entries.isEmpty()) return;
        int n = s.entries.size();
        view = ((view + by) % n + n) % n;
        render();
    }

    // The Galaxy Watch bezel and a Wear OS crown both arrive as rotary scroll events.
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            scrolled += -ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (Math.abs(scrolled) >= 1f) {
                move(scrolled > 0 ? 1 : -1);
                scrolled = 0;
            }
            return true;
        }
        return super.onGenericMotionEvent(ev);
    }

    private void render() {
        Session s = state.session();
        if (s == null || s.entries.isEmpty()) {
            show(empty);
            ((TextView) empty).setText(R.string.no_workout);
            viewFor = null;
            lastCur = -1;
            return;
        }
        if (!s.sid.equals(viewFor) || s.cur != lastCur) {
            view = s.cur;
            viewFor = s.sid;
            lastCur = s.cur;
            askForHeartRate();
        }
        HeartService.start(this, s.sid);
        if (view >= s.entries.size()) view = s.entries.size() - 1;

        Session.Rest rest = state.rest();
        if (rest != null) {
            show(restView);
            int next = s.entries.get(view).firstOpen() >= 0 ? view : s.nextOpenEntry(view);
            restNext.setText(next >= 0 ? s.entries.get(next).name : "");
            renderClock();
            return;
        }
        if (s.allDone()) {
            show(empty);
            ((TextView) empty).setText(R.string.all_done);
            return;
        }
        show(setView);
        Session.Entry e = s.entries.get(view);
        exercise.setText(e.name);
        int i = e.firstOpen();
        if (i < 0) {
            setLabel.setText(R.string.exercise_done);
            setValue.setText("✓");
            done.setEnabled(false);
            done.setAlpha(0.4f);
        } else {
            Session.SetRow row = e.sets.get(i);
            StringBuilder label = new StringBuilder(getString(R.string.set_of, i + 1, e.sets.size()));
            if (row.warm) label.append(" · ").append(getString(R.string.warmup));
            String side = row.openSide();
            if (side != null) label.append(" · ").append(getString("L".equals(side) ? R.string.side_left : R.string.side_right));
            setLabel.setText(label);
            setValue.setText(Session.describe(row, e.mode, s.unit));
            done.setEnabled(true);
            done.setAlpha(1f);
        }
        renderClock();
    }

    private void renderClock() {
        int bpm = HeartService.lastBpm;
        String hr = bpm > 0 ? "♥ " + bpm : "";
        heart.setText(hr);
        restHeart.setText(hr);
        Session.Rest rest = state.rest();
        if (rest == null || restView.getVisibility() != View.VISIBLE) return;
        long left = rest.endsAt > 0 ? rest.endsAt - System.currentTimeMillis() : rest.left * 1000L;
        restClock.setText(Session.clock(left));
        // Over on screen before the alarm's broadcast has landed: the set is next.
        if (rest.endsAt > 0 && left <= -1500) state.restOver(this);
    }

    private void show(View which) {
        empty.setVisibility(which == empty ? View.VISIBLE : View.GONE);
        setView.setVisibility(which == setView ? View.VISIBLE : View.GONE);
        restView.setVisibility(which == restView ? View.VISIBLE : View.GONE);
    }

    // Once, the first time a workout reaches the screen. Denied, the workout works the same; the
    // permission can be given later in the watch's settings.
    private void askForHeartRate() {
        if (HeartService.permitted(this)) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED, false)) return;
        prefs.edit().putBoolean(KEY_ASKED, true).apply();
        requestPermissions(new String[] {Manifest.permission.BODY_SENSORS, Manifest.permission.POST_NOTIFICATIONS}, REQUEST_HEART);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_HEART) return;
        Session s = state.session();
        if (HeartService.permitted(this)) {
            if (s != null) HeartService.start(this, s.sid);
        } else {
            Toast.makeText(this, R.string.heart_denied, Toast.LENGTH_LONG).show();
        }
    }
}
