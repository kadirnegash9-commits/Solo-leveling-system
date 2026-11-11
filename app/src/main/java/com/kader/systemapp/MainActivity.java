package com.kader.systemapp;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
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
    // v4.8: a Saitama alarm opens the app straight onto the full QUEST
    // INFO screen — set from the launch intent, consumed on page load.
    private boolean showQuestInfoOnLoad = false;
    // v5.3: the quest whose alarm opened the app — set from the launch
    // intent, consumed on page load (scroll to it + show STOP ALARM).
    private String ringingQuestOnLoad = null;
    // v5.7.12: ACCEPT ("done") / DECLINE ("missed") tapped in the
    // full-screen System window — set from the launch intent, consumed
    // on page load (decideQuest guards one-tap-only, so a repeated
    // call is a no-op).
    private String questDecisionOnLoad = null;
    // v5.7.22: true once index.html has loaded (onPageFinished) —
    // consumePendingGoalState() only runs against a loaded page.
    private boolean pageLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tapping the alarm notification dismisses the alarm: stop the
        // looping sound. A plain launch (launcher / full-screen auto-open)
        // carries no extra, so the loop keeps ringing.
        checkStopAlarm(getIntent());
        showQuestInfoOnLoad = getIntent() != null
                && getIntent().getBooleanExtra("show_quest_info", false);
        ringingQuestOnLoad = getIntent() != null
                ? getIntent().getStringExtra("ringing_quest") : null;
        questDecisionOnLoad = getIntent() != null
                ? getIntent().getStringExtra("quest_decision") : null;

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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                // v5.7.22: apply the goal board's pending state first
                // (row taps, then completed Saitama quests), before
                // any intent decision below — toggles first so the
                // quest decision sees the finished goals.
                consumePendingGoalState();
                // v4.8: opened from a Saitama alarm — show the full QUEST
                // INFO screen once the page (and its script) is ready.
                if (showQuestInfoOnLoad) {
                    showQuestInfoOnLoad = false;
                    showQuestInfo();
                }
                // v5.3: opened from a quest alarm — scroll to the ringing
                // quest and show the STOP ALARM bar.
                if (ringingQuestOnLoad != null) {
                    String id = ringingQuestOnLoad;
                    ringingQuestOnLoad = null;
                    showRingingQuest(id);
                    // v5.7.12: ACCEPT/DECLINE tapped in the full-screen
                    // System window — apply the decision now.
                    if (questDecisionOnLoad != null) {
                        String act = questDecisionOnLoad;
                        questDecisionOnLoad = null;
                        applyQuestDecision(id, act);
                    }
                }
            }
        });
        webView.loadUrl("file:///android_asset/index.html");

        // Arm the daily quest reminders (QuestReceiver)
        QuestReceiver.scheduleAll(this);

        // v4.7: HiOS can defer even setAlarmClock alarms while the app is
        // frozen in the background, until the user opens the phone — that
        // is the other way an alarm can arrive late. Ask ONCE to whitelist
        // the app from battery optimization so alarms fire exactly on time.
        askBatteryOptimizationOnce();
    }

    // v5.6.4: re-arm on every foreground too, not just onCreate —
    // bringing the app forward from recents must also restore any
    // lost alarm. (Safety net only: the alarms must NOT depend on
    // the app being opened at all — MY_PACKAGE_REPLACED + the
    // 30-min watchdog keep them armed on their own.)
    @Override
    protected void onResume() {
        super.onResume();
        QuestReceiver.scheduleAll(this);
        // v5.7.21: the board can only pop up on an alarm once
        // "Display over other apps" is granted (see below).
        askOverlayPermission();
        // v5.7.22: a goal board closed with Back (or tapped away)
        // leaves its taps recorded — apply them now that the app is
        // in front and the page is loaded. (On a fresh launch the
        // page is not loaded yet here, so onPageFinished does it.)
        if (pageLoaded) consumePendingGoalState();
    }

    // v5.7.21 (boss, 2026-11-11): he INSTALLED v5.7.20 and tested it
    // (Alarm Test) — NO CHANGE, only the small notification pill.
    // The v5.7.20 direct launch (QuestReceiver.startActivity) is
    // BLOCKED by Android/HiOS background-activity-launch rules: the
    // setAlarmClock exemption did NOT let it through on his phone.
    // SYSTEM_ALERT_WINDOW ("Display over other apps") IS a
    // background-launch exemption — once he grants it, the direct
    // launch works and the board pops up on every alarm, even when
    // the phone is unlocked. Shown on every app open until granted
    // (it is a one-time grant); "Later" just dismisses it for now.
    private void askOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            if (Settings.canDrawOverlays(this)) return;
            new AlertDialog.Builder(this)
                    .setTitle("Board pop-up needs permission")
                    .setMessage("For the quest board to pop up when an alarm fires, allow Solo Leveling System to \"Display over other apps\". Without it, an alarm only shows a small notification — the board will not open. Allow it once in Settings.")
                    .setPositiveButton("Allow", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            openOverlaySettings();
                        }
                    })
                    .setNegativeButton("Later", null)
                    .show();
        } catch (Exception ignored) {}
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Apps → Solo Leveling → Display over other apps", Toast.LENGTH_LONG).show();
        }
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
        // v5.7.22: apply the goal board's pending state first (the
        // page is already loaded when a new intent arrives).
        consumePendingGoalState();
        // v4.8: a Saitama alarm tap opens the full QUEST INFO screen
        // (the page is already loaded, so show it right away).
        if (intent != null && intent.getBooleanExtra("show_quest_info", false)) {
            showQuestInfo();
        }
        // v5.3: a quest alarm fired while the app was open — scroll to
        // the ringing quest and show the STOP ALARM bar.
        if (intent != null && intent.getStringExtra("ringing_quest") != null) {
            String id = intent.getStringExtra("ringing_quest");
            showRingingQuest(id);
            // v5.7.12: ACCEPT/DECLINE tapped in the full-screen System
            // window while the app was open — apply it right away.
            if (intent.getStringExtra("quest_decision") != null) {
                applyQuestDecision(id, intent.getStringExtra("quest_decision"));
            }
        }
    }

    // v4.8: show the full QUEST INFO screen (the anime panel in the web
    // app) — called from a Saitama alarm's intent, never on a plain launch.
    private void showQuestInfo() {
        if (webView == null) return;
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                        "window.showQuestInfo && window.showQuestInfo()", null);
            }
        });
    }

    // v5.3: scroll to the ringing quest and show the STOP ALARM bar —
    // called when a quest alarm opens the app, never on a plain launch.
    private void showRingingQuest(final String id) {
        if (webView == null || id == null) return;
        final String safeId = id.replace("'", "");
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                        "window.showRingingQuest && window.showRingingQuest('" + safeId + "')", null);
            }
        });
    }

    // v5.7.22: apply the goal board's pending state, recorded in
    // SharedPreferences by QuestAlarmActivity while the goal board
    // was up: row taps first (window.toggleGoal — the app's own
    // §3 rule, ±2 XP each), then completed Saitama quests
    // (applyQuestDecision 'done' — the existing +10 XP / +10 Gold /
    // +2 HP path; decideQuest guards one-tap-only, so a repeated
    // call is a no-op). Read + clear atomically BEFORE applying,
    // so a second consumption (page load + new intent + resume)
    // can never double-apply a toggle. Entries whose date is not
    // today are dropped, never applied — a tap can never land on
    // the wrong day.
    private void consumePendingGoalState() {
        if (webView == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences("solo_leveling_prefs", MODE_PRIVATE);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US).format(new java.util.Date());
            final java.util.List<String> kinds = new java.util.ArrayList<>();
            final java.util.List<String> quests = new java.util.ArrayList<>();
            String rawToggles = prefs.getString("goal_pending_toggles", null);
            if (rawToggles != null) {
                JSONObject o = new JSONObject(rawToggles);
                if (today.equals(o.optString("date", ""))) {
                    org.json.JSONArray a = o.optJSONArray("kinds");
                    if (a != null) {
                        for (int i = 0; i < a.length(); i++) kinds.add(a.optString(i));
                    }
                }
            }
            String rawDecisions = prefs.getString("goal_pending_decisions", null);
            if (rawDecisions != null) {
                JSONObject o = new JSONObject(rawDecisions);
                if (today.equals(o.optString("date", ""))) {
                    org.json.JSONArray a = o.optJSONArray("quests");
                    if (a != null) {
                        for (int i = 0; i < a.length(); i++) quests.add(a.optString(i));
                    }
                }
            }
            if (kinds.isEmpty() && quests.isEmpty()) {
                // Still clear stale entries so they can never apply later.
                if (rawToggles != null || rawDecisions != null) {
                    prefs.edit().remove("goal_pending_toggles")
                            .remove("goal_pending_decisions").apply();
                }
                return;
            }
            prefs.edit().remove("goal_pending_toggles")
                    .remove("goal_pending_decisions").apply();
            for (final String kind : kinds) {
                if (!"push".equals(kind) && !"sit".equals(kind)
                        && !"squat".equals(kind) && !"run".equals(kind)) continue;
                final String safeKind = kind.replace("'", "");
                webView.post(new Runnable() {
                    @Override public void run() {
                        webView.evaluateJavascript(
                                "window.toggleGoal && window.toggleGoal('" + safeKind + "')", null);
                    }
                });
            }
            for (final String qid : quests) {
                applyQuestDecision(qid, "done");
            }
        } catch (Exception ignored) {}
    }

    // v5.7.12: apply an ACCEPT ("done") / DECLINE ("missed") decision
    // from the full-screen System window. decideQuest guards one-tap-only,
    // so calling it twice (load + new intent) is safe; "alarmtest" is not
    // a real quest, so decideQuest ignores it.
    private void applyQuestDecision(final String id, final String act) {
        if (webView == null || id == null || act == null) return;
        final String safeId = id.replace("'", "");
        final String safeAct = "done".equals(act) ? "done" : "missed";
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                        "window.decideQuest && window.decideQuest('" + safeId + "','" + safeAct + "')", null);
            }
        });
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

    // v5.7.14 (boss, 2026-11-11 — "the notification isn't right, it's too
    // small. The one that comes from above doesn't come like the zip file"):
    // the "notification" the boss sees must be the BIG Gemini board, not
    // the small system banner sliding down from above. The old helper that
    // posted exactly that small banner for the "Notifications allowed"
    // confirmation is deleted; these flows now open the same full-screen
    // Gemini board the quest alarms use (QuestAlarmActivity +
    // alarm-board.html), with the message typewritten inside it. No system
    // notification is posted for them anymore — nothing slides down
    // from above.
    private void showNotificationBoard(String message) {
        Intent board = new Intent(this, QuestAlarmActivity.class);
        board.putExtra("quest_id", "notif-allowed");
        board.putExtra("quest_title", message);
        board.putExtra("board_message", true);
        startActivity(board);
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
                            showNotificationBoard("Notifications allowed — Arise!");
                        }
                    } else {
                        showNotificationBoard("Notifications allowed — Arise!");
                    }
                }
            });
        }

        @JavascriptInterface
        public void testNotification() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showNotificationBoard("notification test"); // v5.7.15: boss — "since now it is a notification test, write notification test"
                }
            });
        }

        @JavascriptInterface
        public boolean notificationsEnabled() {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return manager != null && manager.areNotificationsEnabled();
        }

        // v5.7.21: "Display over other apps" — the board's direct
        // launch from an alarm only works once this is granted (the
        // setAlarmClock exemption did not let it through on his
        // phone). The §6 banner in the web app reads this so the
        // boss can see the state and grant it from the alarm section.
        @JavascriptInterface
        public boolean overlayPermissionGranted() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(MainActivity.this);
            }
            return true;
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    openOverlaySettings();
                }
            });
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

        // v5.7.23: "Goal Test (1 min)" (§6) — schedules the goal test
        // alarm, which pops the goal board as a preview (test mode:
        // writes no state). The boss taps it to see the goal board now
        // instead of waiting for the Thu 05:30 Saitama alarm.
        @JavascriptInterface
        public void testGoalAlarm() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    QuestReceiver.scheduleGoalTestAlarm(MainActivity.this);
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
                showNotificationBoard("Notifications allowed — Arise!");
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
