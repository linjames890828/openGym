package ch.duartesantos.opengym.wear;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Heart rate during the workout: the watch's own sensor, read for as long as the phone's workout
 * runs, sent to the phone in batches. The phone keeps only the average, the lowest and the highest
 * (lib/heart-rate.js), so no series of numbers travels any further than the phone.
 *
 * A foreground service of the health type, so it keeps reading with the screen off. Android only
 * lets the visible app start one that reads a body sensor, so the watch screen starts it
 * (MainActivity) and the end of the workout stops it (WatchState).
 */
public class HeartService extends Service implements SensorEventListener {
    private static final String EXTRA_SID = "sid";
    private static final int NOTIFY_ID = 2;
    private static final long FLUSH_MS = 30_000;
    // A phone out of reach for a long time: the oldest samples go rather than the memory.
    private static final int MAX_BUFFER = 4000;

    /** The last reading, in beats per minute, or 0 while there is none. Read by the screen. */
    static volatile int lastBpm = 0;
    private static volatile String runningFor = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> buffer = new ArrayList<>();
    private SensorManager sensors;
    private String sid;

    // BODY_SENSORS on every version: the app targets 35, and Android 16 (Wear OS 6 and later)
    // grants it as heart-rate access for such an app. Targeting 36 means asking for
    // android.permission.health.READ_HEART_RATE instead.
    static boolean permitted(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
    }

    /** From the visible screen only. Does nothing without the permission or when already reading. */
    static void start(Context ctx, String sid) {
        if (sid == null || sid.equals(runningFor) || !permitted(ctx)) return;
        Intent i = new Intent(ctx, HeartService.class).putExtra(EXTRA_SID, sid);
        try {
            ctx.startForegroundService(i);
        } catch (RuntimeException ignored) {
            // Not allowed from here right now: the next time the screen opens tries again.
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, HeartService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String next = intent == null ? null : intent.getStringExtra(EXTRA_SID);
        if (next == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        WatchState.ensureChannel(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, WatchState.CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.heart_recording))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!next.equals(sid)) {
            // A new workout: what is left of the last one goes to it first.
            flush();
            sid = next;
        }
        runningFor = sid;
        if (sensors == null) {
            sensors = getSystemService(SensorManager.class);
            Sensor hr = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (hr == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            sensors.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL);
            handler.postDelayed(flusher, FLUSH_MS);
        }
        return START_NOT_STICKY;
    }

    private final Runnable flusher = new Runnable() {
        @Override
        public void run() {
            flush();
            handler.postDelayed(this, FLUSH_MS);
        }
    };

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length == 0) return;
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) {
            lastBpm = 0;
            return;
        }
        int bpm = Math.round(event.values[0]);
        if (bpm <= 0) return;
        lastBpm = bpm;
        synchronized (buffer) {
            if (buffer.size() >= MAX_BUFFER) buffer.remove(0);
            buffer.add(bpm);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // Sends what has been read since the last batch. What does not reach the phone goes back in
    // front of the buffer and rides with the next one.
    private void flush() {
        final List<Integer> batch;
        synchronized (buffer) {
            if (buffer.isEmpty() || sid == null) return;
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }
        String json;
        try {
            JSONArray bpm = new JSONArray();
            for (int b : batch) bpm.put(b);
            json = new JSONObject().put("type", "heart").put("sid", sid).put("bpm", bpm).put("at", System.currentTimeMillis()).toString();
        } catch (JSONException e) {
            return;
        }
        Link.send(this, json, sent -> {
            if (sent) return;
            synchronized (buffer) {
                buffer.addAll(0, batch);
                while (buffer.size() > MAX_BUFFER) buffer.remove(0);
            }
        });
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(flusher);
        if (sensors != null) sensors.unregisterListener(this);
        // The last batch: the phone puts it on the workout even after it was saved (lib/watch-sync.js).
        flush();
        lastBpm = 0;
        runningFor = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
