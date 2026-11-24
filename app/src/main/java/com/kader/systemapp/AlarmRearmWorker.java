package com.kader.systemapp;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

// v5.7.36 (boss, 2026-11-24): WorkManager periodic re-arm — the alarm
// chain (setAlarmClock alarms + 30-min watchdog) dies on an app update
// or a HiOS kill/hibernation and never self-heals, because HiOS often
// never delivers MY_PACKAGE_REPLACED and the watchdog dies too (Deep
// Study 13:35 missed Nov 23 + Nov 24, Isha 19:10 missed Nov 24).
// Unlike AlarmManager alarms, WorkManager periodic work is restored by
// the system across app updates even if the app is never opened, so the
// chain self-heals within 15 minutes of an update or hibernation.
// Enqueued once (KEEP) from MainActivity.onCreate; the 15-minute
// interval is the minimum WorkManager allows. Honest limit: a manual
// force-stop still cancels WorkManager work until the next app open —
// no mechanism survives that on this phone. scheduleAll() is
// idempotent (arm-first), so a 15-minute re-arm is harmless.
public class AlarmRearmWorker extends Worker {

    public AlarmRearmWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
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
