package com.kader.systemapp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Calendar;
import java.util.Locale;

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
 * Saturday rule (boss, confirmed 2026-11-07, updated 14:22, Fajr
 * added 14:36): on Saturday only prayer (fajr/zuhr/asr/maghrib/
 * isha) reminders fire — NO sport/gym, all other quests are
 * excused that day, their alarms move to Sunday.
 * Saitama rule (boss, confirmed 2026-11-07; times swapped 2026-11-08):
 * Sun/Tue/Thu the 05:30 exercises + 06:00 run replace the 06:00 sport
 * quest; Mon/Wed/Fri gym only; Saturday: no Saitama.
 */
public class QuestReceiver extends BroadcastReceiver {

    // Alarm-style channel: HIGH importance + alarm sound + vibration, so a
    // quest reminder rings like a clock alarm (and bypasses Do Not Disturb).
    // v2.2 used "solo_leveling" (DEFAULT importance, silent in DND) — deleted.
    public static final String CHANNEL_ID = "solo_leveling_alarm";
    // v4.6: alarm notifications post on this channel, created with NO
    // sound — the looping MediaPlayer is the only sound. (On Android 8+
    // a notification's sound comes from its channel; Builder.setSound(null)
    // falls back to the channel sound, so a silent channel is the only
    // deterministic way to silence it.)
    public static final String SILENT_CHANNEL_ID = "solo_leveling_alarm_silent";
    private static final String OLD_CHANNEL_ID = "solo_leveling";
    // Channel sound can't change after creation (Android restores the
    // deleted channel's old sound when recreated with the same ID), so
    // every sound change gets a NEW channel ID (boss, 2026-11-07).
    private static final String KEY_CHANNEL_VER = "alarm_channel_ver";
    public static String currentChannelId(Context context) {
        int v = 0;
        try {
            v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CHANNEL_VER, 0);
        } catch (Exception ignored) {}
        return v <= 0 ? CHANNEL_ID : CHANNEL_ID + "_v" + v;
    }
    public static final String ACTION_QUEST = "com.kader.systemapp.QUEST_REMINDER";
    // Sent when the alarm notification is swiped away — stop the loop.
    public static final String ACTION_STOP_ALARM = "com.kader.systemapp.STOP_ALARM";

    // {id, HH:MM, title} — same order/times as the web quests
    public static final String[][] QUESTS = {
            {"fajr", "05:00", "Fajr Prayer"},
            {"sport", "06:00", "Sport — Gym / Run"},
            {"breakfast", "07:00", "Breakfast + Get Ready for School"},
            {"school", "07:30", "School — Grade 9"},
            {"zuhr", "12:35", "Zuhr Prayer + Lunch"},
            {"english", "14:30", "English Lesson"},
            {"asr", "15:35", "Asr Prayer + Quran"},
            {"maghrib", "18:05", "Close Shop + Maghrib Prayer"},
            {"isha", "19:10", "Isha Prayer + Dinner"},
            {"study", "20:30", "Home Study"},
            {"sleep", "22:15", "Sleep — Go to Bed on Time"},
            {"saitamaRun", "06:00", "Saitama Daily Routine — Running 10km"},
            {"saitamaEx", "05:30", "Saitama Daily Routine — 100 Push-ups + 100 Sit-ups + 100 Squats"}
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        // Phone restarted: re-arm all quest alarms, no notification.
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            scheduleAll(context);
            return;
        }
        // Alarm notification swiped away: the alarm is dismissed — stop
        // the looping sound. Nothing else to do (alarms stay armed).
        if (ACTION_STOP_ALARM.equals(intent.getAction())) {
            dismissAlarm();
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
        // English quest: speak a motivational phrase out loud (boss,
        // 2026-11-07). The Alarm Test button speaks too, so he can
        // hear the voice today instead of waiting for 14:30.
        if ("english".equals(id) || "alarmtest".equals(id)) {
            speakMotivation(context, goAsync(), id);
        }
    }

    // English motivation, spoken out loud by the phone (boss, 2026-11-07).
    // One phrase per day, rotating. If the phone has no English voice
    // data, this silently does nothing — the alarm still rings.
    private static final String[] MOTIVATION = {
            "Rise and shine! Time for your English lesson. Every word you learn makes you stronger. Arise!",
            "Your English grows a little every single day. Open the app and learn. You can do it!",
            "Small steps every day lead to big changes. English time, forty minutes. Let's go!",
            "Discipline beats motivation. English time is now. You are becoming a hunter of words!",
            "Soon you will speak English with your squad. Today's lesson starts now. Arise!"
    };
    private static final String MOTIVATION_TEST =
            "This is a test. Your English alarm will speak like this. Arise!";

    private static void speakMotivation(final Context context,
                                        final PendingResult pending, String id) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final TextToSpeech[] ttsHolder = new TextToSpeech[1];
        final boolean[] finished = new boolean[1];
        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (finished[0]) return;
                finished[0] = true;
                try {
                    if (ttsHolder[0] != null) { ttsHolder[0].stop(); ttsHolder[0].shutdown(); }
                } catch (Exception ignored) {}
                try { pending.finish(); } catch (Exception ignored) {}
            }
        };
        // Safety net: never hold the receiver more than 25 seconds.
        handler.postDelayed(finish, 25_000);
        final String phrase = "alarmtest".equals(id)
                ? MOTIVATION_TEST
                : MOTIVATION[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % MOTIVATION.length];
        try {
            ttsHolder[0] = new TextToSpeech(context.getApplicationContext(),
                    new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    final TextToSpeech tts = ttsHolder[0];
                    if (status != TextToSpeech.SUCCESS || tts == null) {
                        handler.post(finish);
                        return;
                    }
                    try {
                        tts.setLanguage(Locale.ENGLISH);
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String utteranceId) {}
                            @Override public void onDone(String utteranceId) { handler.post(finish); }
                            @Override public void onError(String utteranceId) { handler.post(finish); }
                        });
                        // Let the alarm ringtone start first, then speak.
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (finished[0]) return;
                                    ttsHolder[0].speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "solo-english");
                                } catch (Exception e) { handler.post(finish); }
                            }
                        }, 2000);
                    } catch (Exception e) { handler.post(finish); }
                }
            });
        } catch (Exception e) { handler.post(finish); }
    }

    /** One-off test alarm, 120s from now, through the exact same code path
     * as the real quest alarms — proves whether alarms fire on this phone. */
    public static void scheduleTestAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, QuestReceiver.class);
        intent.setAction(ACTION_QUEST);
        intent.putExtra("quest_id", "alarmtest");
        intent.putExtra("quest_title", "⏰ Test alarm — 2 minutes is up! Arise!");
        PendingIntent pi = PendingIntent.getBroadcast(context, 999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long when = System.currentTimeMillis() + 120_000;
        PendingIntent showApp = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showApp), pi);
    }

    // Saturday: prayers ONLY, no sport/gym (boss, updated 2026-11-07
    // 14:22 — "no running, no gym on Saturday"; Fajr added 14:36). All other
    // quests are excused on Saturday — no alarm that day.
    private static boolean isSaturdayAllowed(String id) {
        return "fajr".equals(id) || "zuhr".equals(id) || "asr".equals(id)
                || "maghrib".equals(id) || "isha".equals(id);
    }

    // Saitama rule (boss, 2026-11-07; times swapped 2026-11-08):
    // Sun/Tue/Thu the 05:30 exercises + 06:00 run replace the 06:00
    // sport quest; Mon/Wed/Fri gym only.
    // Saturday: no Saitama (prayer + gym only, per the Saturday rule).
    private static boolean isAllowedOn(Calendar cal, String id) {
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY) {
            return isSaturdayAllowed(id);
        }
        boolean saitamaDay = dow == Calendar.SUNDAY || dow == Calendar.TUESDAY
                || dow == Calendar.THURSDAY;
        if ("saitamaRun".equals(id) || "saitamaEx".equals(id)) {
            return saitamaDay;
        }
        if ("sport".equals(id)) {
            return !saitamaDay;
        }
        return true;
    }

    /** Arm one alarm per quest for its next occurrence (today, else the next
     * allowed day — Saturday skips every quest except prayer + sport). */
    public static void scheduleAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // v5.4.1: three quests removed (hand/trading/homework). Removing
        // entries shifts every request code (100+i), so the old list's
        // codes 100-115 no longer line up — codes the new list never
        // re-arms would keep firing as duplicates, and codes reused by
        // a different quest are only overwritten if re-armed. Cancel
        // EVERY old quest alarm first (FLAG_NO_CREATE: only existing
        // ones are touched), then arm the new list. Runs on every
        // scheduleAll call (app open, boot, each delivery), so it is
        // idempotent and a removed quest can never fire again.
        for (int code = 100; code < 100 + 32; code++) {
            Intent old = new Intent(context, QuestReceiver.class);
            old.setAction(ACTION_QUEST);
            PendingIntent oldPi = PendingIntent.getBroadcast(context, code, old,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (oldPi != null) {
                am.cancel(oldPi);
                oldPi.cancel();
            }
        }
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
            // Sunday (and Saturday) = no school: the 07:00 quest is just
            // "Breakfast" — "Get Ready for School" only on school days
            // (boss, 2026-11-08). The title is set from the day the alarm
            // is armed FOR, so it is right when it fires.
            String title = q[2];
            if ("breakfast".equals(q[0])) {
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) title = "Breakfast";
            }
            intent.putExtra("quest_title", title);
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
        // A fresh alarm delivery: not dismissed, loop not started yet.
        alarmDismissed = false;
        nm.notify(tag, 1, buildQuestNotification(context, tag, title, SILENT_CHANNEL_ID));
        // v4.6: loop starts IMMEDIATELY — the notification above is
        // silent, so the looping MediaPlayer is the only sound (boss,
        // 2026-11-07: the 46s WAKEY sound must keep repeating until he
        // dismisses the alarm, like a normal clock alarm).
        // v4.7: hold a WakeLock while the loop starts — without it the
        // system can kill the app process right after onReceive returns,
        // before prepareAsync finishes, and the alarm is completely
        // silent (that is how the 06:30 alarm was "delivered" 17 min
        // late on 2026-11-08: it posted silently and he only saw it
        // when he opened the app at 06:47). The lock releases as soon
        // as the loop is playing (or the fallback fires), 60s max.
        acquireAlarmWakeLock(context);
        startAlarmLoop(context, tag, title);
    }

    // One builder for the quest alarm notification, on any channel —
    // the silent channel (loop is the sound) or the sounding channel
    // (fallback: the channel sound plays once).
    private static Notification buildQuestNotification(Context context, String questId, String title, String channelId) {
        // v4.8: a Saitama alarm opens the app straight onto the full
        // QUEST INFO screen (the anime panel, not a popup). The
        // full-screen intent gets its own request code (3) so
        // scheduleAll's showApp intents (request code 0, created right
        // after this) cannot clobber it via FLAG_UPDATE_CURRENT.
        boolean saitama = "saitamaRun".equals(questId) || "saitamaEx".equals(questId);
        // Full-screen intent (auto-opens the app when the alarm fires):
        // plain open, NO stop extra — the loop must keep ringing.
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // v5.3: every alarm carries its quest id, so the app can scroll
        // straight to the ringing quest and show STOP ALARM. Request
        // code 4 (not 0): scheduleAll's showApp intents use 0 and
        // would wipe this extra via FLAG_UPDATE_CURRENT.
        open.putExtra("ringing_quest", questId);
        if (saitama) open.putExtra("show_quest_info", true);
        PendingIntent fullScreen = PendingIntent.getActivity(context, saitama ? 3 : 4, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Tapping the notification dismisses the alarm: same screen, but
        // with an extra so MainActivity stops the looping sound.
        Intent tap = new Intent(context, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tap.putExtra("stop_alarm_loop", true);
        if (saitama) tap.putExtra("show_quest_info", true);
        PendingIntent tapPending = PendingIntent.getActivity(context, 1, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Swiping the notification away also dismisses the alarm.
        Intent stop = new Intent(context, QuestReceiver.class);
        stop.setAction(ACTION_STOP_ALARM);
        PendingIntent deletePending = PendingIntent.getBroadcast(context, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(context, channelId);
        } else {
            b = new Notification.Builder(context);
        }
        b.setContentTitle("⚔️ Quest time is here")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tapPending)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000})
                .setPriority(Notification.PRIORITY_HIGH)
                .setFullScreenIntent(fullScreen, true)
                .setDeleteIntent(deletePending)
                .setAutoCancel(true);
        return b.build();
    }

    // Looping alarm sound (boss, 2026-11-07): Android plays a
    // notification channel sound ONCE and never loops it, so the loop
    // IS the sound — a looping MediaPlayer playing the chosen sound
    // (WAKEY, ~46s) until the alarm is dismissed (tap the notification,
    // swipe it away, or ACCEPT/DECLINE a quest in the app).
    // v4.6: the loop starts IMMEDIATELY when the alarm fires. v4.5
    // waited ~46s for the channel sound to finish first (delayed
    // Handler callback) — on his phone the process was gone by then,
    // so the sound played once and stopped. No delay now: the
    // notification is silent (silent channel) and the player starts
    // right away, so there is no doubling and no window to miss.
    private static MediaPlayer loopPlayer;
    // v4.7: set true the moment the loop is actually playing; the
    // fallback (below) only fires when this is still false, so the
    // alarm can never be silent AND never doubles when the loop works.
    private static boolean loopStarted = false;
    private static boolean fallbackPosted = false;
    // Set when the user dismisses the alarm (tap / swipe / ACCEPT /
    // DECLINE) so the fallback never rings after a dismissal.
    private static boolean alarmDismissed = false;
    private static PowerManager.WakeLock alarmWakeLock;

    private static void acquireAlarmWakeLock(Context context) {
        try {
            if (alarmWakeLock == null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                alarmWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoloLeveling:alarm");
                alarmWakeLock.setReferenceCounted(false);
            }
            if (!alarmWakeLock.isHeld()) alarmWakeLock.acquire(60_000);
        } catch (Exception ignored) {}
    }

    private static void releaseAlarmWakeLock() {
        try {
            if (alarmWakeLock != null && alarmWakeLock.isHeld()) alarmWakeLock.release();
        } catch (Exception ignored) {}
    }

    // Fallback (v4.7): the loop did not start — ring the alarm once
    // from the sounding channel (the saved WAKEY sound) so the alarm
    // is never silent. The silent notification is cancelled first and
    // re-posted on the sounding channel (same tag/id, so no duplicate);
    // a fresh post plays the channel sound. Only fires when the loop
    // is not playing, so it never doubles with the loop.
    private static void postFallbackAlarm(final Context context, final String tag, final String title) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.cancel(tag, 1);
            nm.notify(tag, 1, buildQuestNotification(context, tag, title, currentChannelId(context)));
        } catch (Exception ignored) {}
    }

    public static void startAlarmLoop(final Context context, final String tag, final String title) {
        stopAlarmLoop();
        loopStarted = false;
        fallbackPosted = false;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fallbackCheck = new Runnable() {
            @Override public void run() {
                if (loopStarted || fallbackPosted || alarmDismissed) {
                    releaseAlarmWakeLock();
                    return;
                }
                fallbackPosted = true;
                postFallbackAlarm(context, tag, title);
                releaseAlarmWakeLock();
            }
        };
        // If the loop is not playing 5s after the alarm fired, ring once
        // from the channel instead of staying silent.
        handler.postDelayed(fallbackCheck, 5000);
        final Uri sound = alarmSound(context);
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(context.getApplicationContext(), sound);
            mp.setLooping(true);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    // Stopped before it was ready: do not start.
                    if (loopPlayer != p) { releaseAlarmWakeLock(); return; }
                    try {
                        p.start();
                        loopStarted = true;
                    } catch (Exception ignored) {}
                    releaseAlarmWakeLock();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) {
                    try { p.release(); } catch (Exception ignored) {}
                    if (loopPlayer == p) loopPlayer = null;
                    releaseAlarmWakeLock();
                    handler.post(fallbackCheck);
                    return true;
                }
            });
            loopPlayer = mp;
            mp.prepareAsync();
        } catch (Exception ignored) {
            // prepareAsync never started — the delayed check fires the fallback.
        }
    }

    public static void stopAlarmLoop() {
        MediaPlayer mp = loopPlayer;
        loopPlayer = null;
        if (mp != null) {
            try { if (mp.isPlaying()) mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
    }

    // The user dismissed the alarm (tap the notification, swipe it away,
    // or ACCEPT/DECLINE a quest): stop the sound and make sure the
    // fallback never rings afterwards.
    public static void dismissAlarm() {
        alarmDismissed = true;
        stopAlarmLoop();
        releaseAlarmWakeLock();
    }

    // The user's chosen alarm sound (picked in the app's Notifications
    // section via the system ringtone picker, boss 2026-11-07), or the
    // system default alarm when nothing is chosen yet. Stored in
    // SharedPreferences so the receiver (which runs without the UI)
    // can read it when an alarm fires.
    private static final String PREFS = "solo_leveling_prefs";
    private static final String KEY_SOUND = "alarm_sound_uri";

    public static Uri alarmSound(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String saved = prefs.getString(KEY_SOUND, null);
            if (saved != null && !saved.isEmpty()) {
                Uri u = Uri.parse(saved);
                if (u != null) return u;
            }
        } catch (Exception ignored) {}
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return u != null ? u : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    // Display name of the current alarm sound (shown in the app).
    public static String alarmSoundName(Context context) {
        try {
            android.media.Ringtone r = RingtoneManager.getRingtone(context, alarmSound(context));
            if (r != null) {
                String t = r.getTitle(context);
                if (t != null && !t.isEmpty()) return t;
            }
        } catch (Exception ignored) {}
        return "Default alarm";
    }

    // Save the picked sound and rebuild the channel with it — a
    // channel's sound can't change after creation, and recreating with
    // the same ID restores the old sound (Android), so every sound
    // change moves to a NEW channel ID.
    public static void setAlarmSound(Context context, String uriString) {
        String oldId = currentChannelId(context);
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int v = prefs.getInt(KEY_CHANNEL_VER, 0);
            prefs.edit().putString(KEY_SOUND, uriString == null ? "" : uriString)
                    .putInt(KEY_CHANNEL_VER, v + 1).apply();
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.deleteNotificationChannel(oldId);
        }
        ensureChannel(context);
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Drop the old silent v2.2 channel so only the alarm channel remains.
        nm.deleteNotificationChannel(OLD_CHANNEL_ID);
        // v4.0 migration: a custom sound saved under the original channel
        // ID never actually played (Android restores the old sound when a
        // channel is recreated with the same ID). Move to a new channel ID
        // once so the saved sound takes effect.
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String saved = prefs.getString(KEY_SOUND, null);
            int v = prefs.getInt(KEY_CHANNEL_VER, 0);
            if (v == 0 && saved != null && !saved.isEmpty()) {
                nm.deleteNotificationChannel(CHANNEL_ID);
                prefs.edit().putInt(KEY_CHANNEL_VER, 1).apply();
            }
        } catch (Exception ignored) {}
        NotificationChannel ch = new NotificationChannel(
                currentChannelId(context), "Solo Leveling Alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Quest alarms — ring like a clock alarm");
        ch.setSound(alarmSound(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
        nm.createNotificationChannel(ch);
        // v4.6: silent channel for the alarm notifications themselves —
        // the looping MediaPlayer is the only sound, so this channel has
        // none. HIGH importance + vibration so it still shows and rings
        // visibly like a clock alarm.
        NotificationChannel silent = new NotificationChannel(
                SILENT_CHANNEL_ID, "Solo Leveling Alarms", NotificationManager.IMPORTANCE_HIGH);
        silent.setDescription("Quest alarms — sound loops from the app");
        silent.setSound(null, null);
        silent.enableVibration(true);
        silent.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
        nm.createNotificationChannel(silent);
    }
}
