package com.kader.systemapp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import java.util.Calendar;

/**
 * Posts a quest reminder at each daily-plan time and (re)arms the alarms.
 * Wiring: AndroidManifest declares the receiver (+ BOOT_COMPLETED) and the
 * SCHEDULE_EXACT_ALARM / RECEIVE_BOOT_COMPLETED / USE_FULL_SCREEN_INTENT /
 * VIBRATE permissions; MainActivity calls QuestReceiver.scheduleAll(this)
 * in onCreate. Every delivered reminder re-arms all alarms, so the chain
 * survives even if the app is not opened daily.
 * Alarms use AlarmManager.setAlarmClock: the system treats them as real
 * clock alarms (status-bar alarm icon, fires exactly even in Doze, needs
 * NO exact-alarm permission), and the reminder posts as an alarm-category
 * notification with the alarm ringtone, vibration and a full-screen intent
 * — it rings like a clock alarm. Times/titles must match BASE_QUESTS in
 * app/src/main/assets/index.html. BOOT_COMPLETED re-arms after a reboot.
 * Saturday rule (boss, confirmed 2026-11-07): on Saturday only prayer
 * (zuhr/asr/maghrib/isha) + sport reminders fire — all other quests are
 * excused that day, their alarms move to Sunday.
 */
public class QuestReceiver extends BroadcastReceiver {

    // Alarm-style channel: HIGH importance + alarm sound + vibration, so a
    // quest reminder rings like a clock alarm (and bypasses Do Not Disturb).
    // v2.2 used "solo_leveling" (DEFAULT importance, silent in DND) — deleted.
    public static final String CHANNEL_ID = "solo_leveling_alarm";
    private static final String OLD_CHANNEL_ID = "solo_leveling";
    public static final String ACTION_QUEST = "com.kader.systemapp.QUEST_REMINDER";

    // {id, HH:MM, title} — same order/times as the web quests
    public static final String[][] QUESTS = {
            {"sport", "06:00", "ስፖርት — Gym / ሩጫ"},
            {"breakfast", "07:00", "ቁርስ + ለትምህርት ቤት መዘጋጀት"},
            {"school", "08:00", "ትምህርት ቤት — 9ኛ ክፍል"},
            {"zuhr", "12:30", "ዙህር ጸሎት + ምሳ"},
            {"english", "14:30", "English ትምህርት"},
            {"asr", "16:20", "አስር ጸሎት + ቁርኣን"},
            {"hand", "16:40", "Handwriting ልምምድ ✍"},
            {"trading", "17:45", "Trading — ዝግጅት"},
            {"maghrib", "18:00", "ሱቅ ዝጋ + መግሪብ ጸሎት"},
            {"study", "18:45", "የቤት ጥናት"},
            {"isha", "20:15", "ኢሻ ጸሎት + እራት"},
            {"homework", "21:00", "Homework + ክለሳ"},
            {"sleep", "22:15", "እንቅልፍ — በሰዓቱ ተኛ"}
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        // Phone restarted: re-arm all quest alarms, no notification.
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            scheduleAll(context);
            return;
        }
        String id = intent.getStringExtra("quest_id");
        String title = intent.getStringExtra("quest_title");
        if (title == null) return;
        // Weekends = no school (his daily-plan rule)
        if ("school".equals(id)) {
            int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return;
        }
        notifyQuest(context, id == null ? "quest" : id, title);
        // Keep the chain alive even if the app is not opened daily.
        scheduleAll(context);
    }

    /** One-off test alarm, 60s from now, through the exact same code path
     * as the real quest alarms — proves whether alarms fire on this phone. */
    public static void scheduleTestAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, QuestReceiver.class);
        intent.setAction(ACTION_QUEST);
        intent.putExtra("quest_id", "alarmtest");
        intent.putExtra("quest_title", "⏰ Test alarm — 1 ደቂቃ ሞልቶአል! Arise!");
        PendingIntent pi = PendingIntent.getBroadcast(context, 999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long when = System.currentTimeMillis() + 60_000;
        PendingIntent showApp = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showApp), pi);
    }

    // Saturday: only prayer + gym reminders (his rule, confirmed 2026-11-07).
    // All other quests are excused on Saturday — no alarm that day.
    private static boolean isSaturdayAllowed(String id) {
        return "sport".equals(id) || "zuhr".equals(id) || "asr".equals(id)
                || "maghrib".equals(id) || "isha".equals(id);
    }

    private static boolean isAllowedOn(Calendar cal, String id) {
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
            return isSaturdayAllowed(id);
        }
        return true;
    }

    /** Arm one alarm per quest for its next occurrence (today, else the next
     * allowed day — Saturday skips every quest except prayer + sport). */
    public static void scheduleAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < QUESTS.length; i++) {
            String[] q = QUESTS[i];
            int hour = Integer.parseInt(q[1].substring(0, 2));
            int minute = Integer.parseInt(q[1].substring(3, 5));
            Calendar cal = Calendar.getInstance();
            while (!isAllowedOn(cal, q[0])) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                } while (!isAllowedOn(cal, q[0]));
            }
            Intent intent = new Intent(context, QuestReceiver.class);
            intent.setAction(ACTION_QUEST);
            intent.putExtra("quest_id", q[0]);
            intent.putExtra("quest_title", q[2]);
            PendingIntent pi = PendingIntent.getBroadcast(context, 100 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Real clock alarm: exact even in Doze, shows the alarm icon,
            // needs no exact-alarm permission. Tapping the icon opens the app.
            PendingIntent showApp = PendingIntent.getActivity(context, 0,
                    new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(cal.getTimeInMillis(), showApp), pi);
        }
    }

    public static void notifyQuest(Context context, String tag, String title) {
        ensureChannel(context);
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(context, CHANNEL_ID);
        } else {
            b = new Notification.Builder(context);
        }
        b.setContentTitle("⚔️ Quest ጊዜው ደርሷል")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pending)
                .setCategory(Notification.CATEGORY_ALARM)
                .setSound(alarmSound())
                .setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000})
                .setPriority(Notification.PRIORITY_HIGH)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true);
        nm.notify(tag, 1, b.build());
    }

    private static Uri alarmSound() {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return u != null ? u : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Drop the old silent v2.2 channel so only the alarm channel remains.
        nm.deleteNotificationChannel(OLD_CHANNEL_ID);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Solo Leveling Alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Quest alarms — ring like a clock alarm");
        ch.setSound(alarmSound(), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
        nm.createNotificationChannel(ch);
    }
}
