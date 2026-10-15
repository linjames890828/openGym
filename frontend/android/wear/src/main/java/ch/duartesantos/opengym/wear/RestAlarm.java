package ch.duartesantos.opengym.wear;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.VibrationEffect;
import android.os.VibratorManager;

/**
 * The buzz at the end of a rest, on the wrist, whether or not the watch app is on screen. An alarm
 * rather than a timer in the app: the screen goes off between sets and the app with it.
 */
final class RestAlarm {
    private static final int REQUEST = 1;
    // Long, short, long: unlike a notification's single buzz.
    private static final long[] PATTERN = {0, 400, 150, 400, 150, 600};

    private RestAlarm() {}

    static void schedule(Context context, long at) {
        Context ctx = context.getApplicationContext();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = intent(ctx);
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    static void cancel(Context context) {
        Context ctx = context.getApplicationContext();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(intent(ctx));
    }

    private static PendingIntent intent(Context ctx) {
        return PendingIntent.getBroadcast(ctx, REQUEST, new Intent(ctx, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void buzz(Context ctx) {
        VibratorManager vm = ctx.getSystemService(VibratorManager.class);
        if (vm == null) return;
        vm.getDefaultVibrator().vibrate(VibrationEffect.createWaveform(PATTERN, -1));
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            buzz(context);
            WatchState.get(context).restOver(context);
        }
    }
}
