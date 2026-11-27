package com.kader.systemapp;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

// v5.7.36 (boss, 2026-11-24): WorkManager periodic re-arm — the alarm
// chain (setAlarmClock alarms + 30-min watchdog) dies on an app update
// or a HiOS kill/hibernation and never self-heals, because HiOS often
// never delivers MY_PACKAGE_REPLACED and the watchdog dies too (Deep
// Study 13:35 missed Nov 23 + Nov 24, Isha 19:10 missed Nov 24).
// Unlike AlarmManager alarms, WorkManager periodic work is restored by
// the system across app updates even if the app is never opened, so the
// chain self-heals within 15 minutes of an update or hibernation.
// Enqueued (KEEP) from every entry point via enqueueRearmWork
// (v5.7.37); the 15-minute interval is the minimum WorkManager allows. Honest limit: a manual
// force-stop still cancels WorkManager work until the next app open —
// no mechanism survives that on this phone. scheduleAll() is
// idempotent (arm-first), so a 15-minute re-arm is harmless.
public class AlarmRearmWorker extends Worker {

    public AlarmRearmWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // v5.7.37 (boss, 2026-11-27): the 15-min re-arm must be (re)enqueued
    // from EVERY entry point, not just MainActivity.onCreate — Thu
    // 2026-11-26 the Deep Study 13:35, Write My Notes 14:35 and PUBG
    // Part 1 17:00 alarms all missed while the morning alarms rang, so
    // the v5.7.36 single-enqueue chain still dies intermittently
    // (HiOS kill / hibernation can drop the periodic work and no app
    // open ever re-enqueues it). One shared helper, KEEP policy: a
    // second enqueue never resets the 15-min clock, so calling it from
    // boot, update, watchdog, onCreate and onResume is harmless.
    public static void enqueueRearmWork(Context context) {
        if (context == null) return;
        try {
            PeriodicWorkRequest rearmRequest =
                    new PeriodicWorkRequest.Builder(
                            AlarmRearmWorker.class, 15, TimeUnit.MINUTES)
                            .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "alarm-rearm", ExistingPeriodicWorkPolicy.KEEP, rearmRequest);
        } catch (Exception ignored) {}
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            QuestReceiver.scheduleAll(getApplicationContext());
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
