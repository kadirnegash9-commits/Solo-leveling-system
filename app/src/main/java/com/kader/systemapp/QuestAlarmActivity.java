package com.kader.systemapp;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONObject;

/**
 * v5.7.13: the full-screen System quest window is now the Gemini
 * notification board itself (boss, 2026-11-11 — "make the notification
 * board when an alarm notification arrives, exactly as in the zip file,
 * nothing else touched").
 *
 * The window is a WebView hosting app/src/main/assets/alarm-board.html:
 * the kader-notif-overlay copied from the Gemini file (index_9) — the
 * horizontal Solo Leveling board frame (board-frame.jpg), "!"
 * + NOTIFICATION horizontal at the top, "PLAYER: KADER NEGASH"
 * vertical down the right edge, the triggered quest's habit typewritten
 * inside the board with the typing tick, ACCEPT / DECLINE below the
 * board, and a REPLAY ANIMATION + SOUND: ON row. All board sounds
 * (open/accept/click/whoosh/close MP3 clips + the typing tick) are the
 * board's own sounds, bundled offline — they are SEPARATE from the
 * alarm: the WAKEY loop and the System ding (QuestReceiver) play
 * unchanged and are never touched here.
 *
 * ACCEPT/DECLINE are final for the day, exactly as in v5.7.12: the tap
 * stops the alarm loop and opens MainActivity, which applies the
 * decision in the web app (quest_decision extra -> window.decideQuest;
 * the web app guards one-tap-only, so a repeated call is a no-op).
 * 100% English, zero Amharic — the app rule. Back closes the window
 * only — the alarm keeps ringing until stopped.
 */
public class QuestAlarmActivity extends Activity {

    private static final int BG = 0xFF000000;

    private String questId = "quest";
    private String questTitle = "Quest time is here";
    // v5.7.12 passed the quest's HH:MM; the Gemini board has no time
    // line, so it is read and kept for the intent contract but not shown.
    private String questTime = "";
    private boolean saitama = false;

    private WebView webView;
    private boolean boardShown = false;

    // Gemini kaderHabitsList order (index_9): the triggered quest's task.
    // -1 = no habit for this quest (e.g. the 2-minute test alarm) — the
    // board then typewrites the quest title itself (see showAlarmBoard).
    private static int habitIndexForQuest(String questId) {
        if (questId == null) return -1;
        if ("fajr".equals(questId)) return 0;
        if ("saitamaEx".equals(questId)) return 1;
        if ("sport".equals(questId)) return 2;
        if ("saitamaRun".equals(questId)) return 3;
        if ("breakfast".equals(questId)) return 4;
        if ("school".equals(questId)) return 5;
        if ("zuhr".equals(questId)) return 6;
        if ("english".equals(questId)) return 7;
        if ("asr".equals(questId)) return 8;
        if ("maghrib".equals(questId)) return 12;
        if ("study".equals(questId)) return 13;
        if ("isha".equals(questId)) return 14;
        if ("sleep".equals(questId)) return 16;
        return -1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.getStringExtra("quest_id") != null) {
                questId = intent.getStringExtra("quest_id");
            }
            if (intent.getStringExtra("quest_title") != null) {
                questTitle = intent.getStringExtra("quest_title");
            }
            if (intent.getStringExtra("quest_time") != null) {
                questTime = intent.getStringExtra("quest_time");
            }
            saitama = intent.getBooleanExtra("show_quest_info", false);
        }
        // Show over the lock screen and turn the screen on, like a clock alarm.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        // Full-screen immersive: hide status + navigation bars.
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // The board plays its open MP3 + typing tick as soon as it
        // appears — no user gesture has happened yet at alarm time.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        // Local asset page only (no remote content): let its frame
        // image + MP3 clips load as relative file:// subresources.
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AlarmBridge(), "AndroidAlarm");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                showBoard();
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl("file:///android_asset/alarm-board.html");
    }

    // Open the board with this alarm's habit (once — onPageFinished can
    // fire again on a reload; the board's own 900ms fallback covers a
    // missed call, so a second call here is harmless but skipped).
    private void showBoard() {
        if (boardShown || webView == null) return;
        boardShown = true;
        final int idx = habitIndexForQuest(questId);
        final String title = questTitle == null ? "" : questTitle;
        final String js;
        try {
            js = "window.showAlarmBoard(" + idx + ", " + JSONObject.quote(title) + ");";
        } catch (Exception e) {
            return;
        }
        try {
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    // Called from the board's ACCEPT / DECLINE buttons (after the
    // 390ms vanish animation): stop the alarm loop, clear the
    // notification, and open the app, which applies the decision in
    // the web app (quest_decision extra). One tap only — decideQuest in
    // the web app ignores a second decision for the same quest today.
    private class AlarmBridge {
        @JavascriptInterface
        public void decide(final String act) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    decideQuest(act);
                }
            });
        }
    }

    private void decideQuest(String act) {
        QuestReceiver.dismissAlarm();
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(questId, 1);
        } catch (Exception ignored) {}
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("ringing_quest", questId);
        open.putExtra("quest_decision", act);
        if (saitama) open.putExtra("show_quest_info", true);
        startActivity(open);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Close the window only — the alarm keeps ringing until it is
        // stopped (notification tap/swipe, or ACCEPT/DECLINE).
        finish();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.removeJavascriptInterface("AndroidAlarm");
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
