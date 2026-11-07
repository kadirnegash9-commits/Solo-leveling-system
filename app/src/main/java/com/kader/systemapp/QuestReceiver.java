package com.kader.systemapp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;

/**
 * Posts a quest reminder at each daily-plan time and (re)arms the alarms.
 * Wiring (2 small edits, not yet applied):
 *   1. AndroidManifest.xml, inside <application>:
 *        <receiver android:name=".QuestReceiver" android:exported="false" />
 *      plus: <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
 *            <uses-permission android:name="android.permission.INTERNET" />
 *   2. MainActivity.onCreate(), after webView.loadUrl(...):
 *        QuestReceiver.scheduleAll(this);
 * Until wired, this file compiles but does nothing.
 * Times/titles must match BASE_QUESTS in app/src/main/assets/index.html.
 * Limitation: alarms are lost on reboot until the app is opened once.
 */
public class QuestReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "solo_leveling";
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
        String id = intent.getStringExtra("quest_id");
        String title = intent.getStringExtra("quest_title");
        if (title == null) return;
        // Weekends = no school (his daily-plan rule)
        if ("school".equals(id)) {
            int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return;
        }
        notifyQuest(context, id == null ? "quest" : id, title);
    }

    /** Arm one alarm per quest for its next occurrence (today, else tomorrow). */
    public static void scheduleAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < QUESTS.length; i++) {
            String[] q = QUESTS[i];
            int hour = Integer.parseInt(q[1].substring(0, 2));
            int minute = Integer.parseInt(q[1].substring(3, 5));
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            Intent intent = new Intent(context, QuestReceiver.class);
            intent.setAction(ACTION_QUEST);
            intent.putExtra("quest_id", q[0]);
            intent.putExtra("quest_title", q[2]);
            PendingIntent pi = PendingIntent.getBroadcast(context, 100 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
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
                .setAutoCancel(true);
        nm.notify(tag, 1, b.build());
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Solo Leveling", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Quest reminders from the System");
        nm.createNotificationChannel(ch);
    }
}
