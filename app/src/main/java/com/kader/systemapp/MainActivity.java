package com.kader.systemapp;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String CHANNEL_ID = "solo_leveling_alarm";
    private static final String OLD_CHANNEL_ID = "solo_leveling";
    private static final int NOTIF_PERMISSION_CODE = 1001;
    private static final int RINGTONE_PICKER_CODE = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tapping the alarm notification dismisses the alarm: stop the
        // looping sound. A plain launch (launcher / full-screen auto-open)
        // carries no extra, so the loop keeps ringing.
        checkStopAlarm(getIntent());

        // Alarm-style: show over the lock screen and turn the screen on,
        // like a clock alarm, when a quest reminder fires.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        createNotificationChannel();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF050914);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.addJavascriptInterface(new SystemBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");

        // Arm the daily quest reminders (QuestReceiver)
        QuestReceiver.scheduleAll(this);

        // v4.7: HiOS can defer even setAlarmClock alarms while the app is
        // frozen in the background, until the user opens the phone — that
        // is the other way an alarm can arrive late. Ask ONCE to whitelist
        // the app from battery optimization so alarms fire exactly on time.
        askBatteryOptimizationOnce();
    }

    // One-time system prompt: "Allow Solo Leveling System to run in the
    // background without battery optimization?" — the user taps Allow.
    // If he declines (or the phone does not offer it), never ask again.
    private void askBatteryOptimizationOnce() {
        try {
            SharedPreferences prefs = getSharedPreferences("solo_leveling_prefs", MODE_PRIVATE);
            if (prefs.getBoolean("battery_opt_asked", false)) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                prefs.edit().putBoolean("battery_opt_asked", true).apply();
                return;
            }
            prefs.edit().putBoolean("battery_opt_asked", true).apply();
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    // The alarm notification is SINGLE_TOP: when the app is already open,
    // a tap arrives here instead of onCreate.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkStopAlarm(intent);
    }

    private void checkStopAlarm(Intent intent) {
        try {
            if (intent != null && intent.getBooleanExtra("stop_alarm_loop", false)) {
                QuestReceiver.dismissAlarm();
            }
        } catch (Exception ignored) {}
    }

    // One place builds the alarm channel, so the user's chosen sound
    // (saved in QuestReceiver) is always the one that gets used.
    private void createNotificationChannel() {
        QuestReceiver.ensureChannel(this);
    }

    private void postNotification(String title, String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(this, QuestReceiver.currentChannelId(this));
        } else {
            builder = new android.app.Notification.Builder(this);
        }
        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pending)
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .setSound(QuestReceiver.alarmSound(this))
                .setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000})
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .setAutoCancel(true);
        manager.notify(1, builder.build());
    }

    public class SystemBridge {
        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(
                                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                                    NOTIF_PERMISSION_CODE);
                        } else {
                            postNotification("⚔️ The System", "Notifications allowed — Arise!");
                        }
                    } else {
                        postNotification("⚔️ The System", "Notifications allowed — Arise!");
                    }
                }
            });
        }

        @JavascriptInterface
        public void testNotification() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    postNotification("⚔️ The System", "Test notification — don't forget your quests! Arise!");
                }
            });
        }

        @JavascriptInterface
        public boolean notificationsEnabled() {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return manager != null && manager.areNotificationsEnabled();
        }

        @JavascriptInterface
        public boolean exactAlarmsAllowed() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        }

        @JavascriptInterface
        public void testAlarm() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    QuestReceiver.scheduleTestAlarm(MainActivity.this);
                }
            });
        }

        // Called by the web app when a quest is ACCEPTED or DECLINED —
        // tapping a quest dismisses the alarm, so the looping sound stops.
        @JavascriptInterface
        public void stopAlarmSound() {
            QuestReceiver.dismissAlarm();
        }

        // Alarm sound picker (boss, 2026-11-07): opens the phone's own
        // ringtone list (alarm sounds) — no audio files in the app.
        @JavascriptInterface
        public void pickAlarmSound() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
                        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound — Solo Leveling");
                        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
                        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                QuestReceiver.alarmSound(MainActivity.this));
                        startActivityForResult(intent, RINGTONE_PICKER_CODE);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Ringtone picker did not open", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public String alarmSoundName() {
            return QuestReceiver.alarmSoundName(MainActivity.this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RINGTONE_PICKER_CODE && resultCode == RESULT_OK && data != null) {
            Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (picked != null) {
                QuestReceiver.setAlarmSound(this, picked.toString());
                final String name = QuestReceiver.alarmSoundName(this);
                Toast.makeText(this, "Alarm sound: " + name, Toast.LENGTH_LONG).show();
                if (webView != null) {
                    final String js = "window.onAlarmSoundPicked && window.onAlarmSoundPicked("
                            + JSONObject.quote(name) + ")";
                    webView.post(new Runnable() {
                        @Override
                        public void run() { webView.evaluateJavascript(js, null); }
                    });
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIF_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                postNotification("⚔️ The System", "Notifications allowed — Arise!");
            } else {
                Toast.makeText(this, "Notifications denied — Settings → Notifications", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
