package com.kader.systemapp;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * The window is a WebView hosting app/src/main/assets/alarm-board-v19.html:
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
 *
 * v5.7.14: message mode — MainActivity now opens this same board for
 * the "Notifications allowed" confirmation (and the test notification)
 * instead of posting a small system banner: the board typewrites the
 * message itself, and ACCEPT/DECLINE only dismiss it.
 *
 * v5.7.15 (boss, 2026-11-11 — "the board was in the middle, no sound,
 * no replay animation, no anime writing, not like Gemini"):
 * - The board now sits at the TOP of the screen and slides down from
 *   above, full-width and BIG (alarm-board.html) — never in the middle.
 * - SOUND: the board's sounds were silent in v5.7.13/14 because the
 *   board script never declared `audioEnabled`, so every sound call
 *   threw ReferenceError — which also killed the typewriter (it ran
 *   after the sound call) and killed ACCEPT/DECLINE (the decide call
 *   came after the sound call). Fixed in the board script AND made
 *   reliable here: the 5 board MP3 clips + the typing tick now play
 *   NATIVELY through SoundPool (same reliable path as the WAKEY loop
 *   and the System ding), with the WebView audio only as fallback.
 * - The REPLAY ANIMATION / SOUND: ON pills below the board are deleted;
 *   tapping the board itself replays the animation, sound is always on.
 * - The old 900ms JS auto-open fallback is deleted: it raced
 *   onPageFinished and showed the WRONG habit (Fajr) at noon. A Java
 *   1500ms safety re-call of showBoard() with the REAL quest replaces it.
 *
 * v5.7.16 (boss, 2026-11-11 — board back to the MIDDLE): the overlay
 * is justify-content:center again (like the Gemini zip file), the
 * sounds were re-cut CLEAN from the SoloLevelX MP3 chime segments,
 * and scheduleAll now arms new alarms FIRST (so an update can never
 * leave alarms cancelled — Zuhr 12:35 was lost minutes after the
 * v5.7.15 install).
 *
 * v5.7.17 (boss, 2026-11-11 — he INSTALLED v5.7.16 but the board
 * was STILL at the top): the remote v5.7.16 board was verified correct
 * (justify-content:center), so the cause was the WebView serving a
 * CACHED copy of the v5.7.15 board — file:///android_asset/ pages are
 * cached in the app's cache dir, which SURVIVES app updates. Fix: the
 * WebView now clears its cache and never loads from it, and the board
 * loads with a version query, so every version has its own cache key.
 * Board look/position/sounds are UNCHANGED (middle, full-width, clean
 * sounds) — this build is the cache fix only.
 *
 * v5.7.18 (boss, 2026-11-11 — he INSTALLED v5.7.17, "just now" APK,
 * and the board was STILL at the top): the ?v=5.7.17 query did NOT
 * bust the cache — a query on a file:// URL does not create a fresh
 * cache key, and clearCache(true) is async, so the stale board kept
 * serving. Fix: the board asset is RENAMED (alarm-board-v18.html) —
 * a new filename cannot be served from any cache. A tiny "v5.7.18"
 * marker sits in the board's bottom-right corner so the boss can see
 * WHICH version is rendering. Also fixed: message/test boards kept
 * showing the Fajr habit on replay / ACCEPT / DECLINE — showAlarmBoard
 * set currentKaderHabitIdx = 0 for title-only boards; replay and
 * decide now use the habit object actually on the board.
 *
 * v5.7.19 (boss, 2026-11-11 — he INSTALLED v5.7.18, "just now" APK,
 * and the board was STILL at the top, but the "v5.7.18" marker showed
 * bottom-right, so the new file IS loading — the cache is beaten):
 * the flex centering does NOT take effect on his phone even though
 * the CSS is correct. Fix: the frame is wrapped in a
 * .kader-board-wrap div pinned to the middle by ABSOLUTE positioning
 * (top:50% + left:50% + translate(-50%,-50%)); the overlay also gets
 * height:100% alongside 100vh so it is full-height without relying
 * on the vh unit. The board asset is RENAMED again
 * (alarm-board-v19.html) — the per-version filename that beat the
 * cache. Marker updated to "v5.7.19".
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
    // v5.7.15: native board sounds (SoundPool) — the 5 Gemini board MP3
    // clips bundled in assets/board-*.mp3, plus the typing tick (the
    // short click clip). SoundPool is the reliable path: WebView audio
    // was silent for two versions. USAGE_ALARM so the sounds play
    // over the alarm loop and through Do Not Disturb, like the ding.
    private SoundPool boardSounds;
    private final java.util.Map<String, Integer> boardSoundIds = new java.util.HashMap<>();
    private final java.util.Map<String, Boolean> boardSoundLoaded = new java.util.HashMap<>();
    private final Handler boardSoundHandler = new Handler(Looper.getMainLooper());
    // v5.7.14: message mode — the board is a plain notification message
    // (e.g. the "Notifications allowed" confirmation), not a quest alarm.
    // ACCEPT/DECLINE then only dismiss the board (and stop any alarm loop
    // that happens to be running) — no quest decision is applied and
    // MainActivity is not re-launched, because the user is already in it.
    private boolean messageMode = false;

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
            messageMode = intent.getBooleanExtra("board_message", false);
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
        // v5.7.18: CACHE FIX (v5.7.17's ?v= query did NOT bust the
        // cache — a query on a file:// URL is not a fresh cache key,
        // and clearCache(true) is async). The board asset is RENAMED
        // each version now (alarm-board-v19.html) — a new filename
        // cannot be served from any cache. clearCache + LOAD_NO_CACHE
        // stay as belt-and-braces; the board must always be the
        // CURRENT assets, never a stale copy.
        webView.clearCache(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
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
        initBoardSounds();
        // v5.7.19: new filename = fresh cache key (see above — the
        // ?v= query on the old filename did not bust the cache).
        webView.loadUrl("file:///android_asset/alarm-board-v19.html");
        // v5.7.15 safety net: if onPageFinished is ever missed, re-open
        // the board with the REAL quest after 1500ms (showBoard() is a
        // no-op once the board is shown — it can never show a wrong
        // habit, unlike the old JS fallback).
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { showBoard(); }
        }, 1500);
    }

    // v5.7.15: load the board's own MP3 clips into a SoundPool so the
    // board's sounds actually play (WebView audio was silent in
    // v5.7.13/14). Loading is async — playBoardSound() retries once
    // shortly if a clip is not loaded yet when the board opens.
    private void initBoardSounds() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            boardSounds = new SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(attrs)
                    .build();
            // v5.7.16: the typing tick is its own clip now — a clean,
            // crisp 80ms slice of the chime attack (board-tick.mp3),
            // NOT the 0.5s low click thump (that was muddy when fired
            // ~43x during typewriting). Sound is always on, no toggle.
            final String[] clips = {"open", "accept", "click", "whoosh", "close", "tick"};
            for (String clip : clips) {
                try {
                    AssetFileDescriptor afd = getAssets().openFd("board-" + clip + ".mp3");
                    int id = boardSounds.load(afd, 1);
                    afd.close();
                    boardSoundIds.put(clip, id);
                    boardSoundLoaded.put(clip, false);
                } catch (Exception ignored) {}
            }
            boardSounds.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                    for (java.util.Map.Entry<String, Integer> e : boardSoundIds.entrySet()) {
                        if (e.getValue() == sampleId) boardSoundLoaded.put(e.getKey(), status == 0);
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    private void playBoardSoundImpl(final String clip) {
        if (boardSounds == null || clip == null) return;
        final Integer id = boardSoundIds.get(clip);
        if (id == null) return;
        Boolean loaded = boardSoundLoaded.get(clip);
        if (loaded != null && loaded) {
            try { boardSounds.play(id, 1.0f, 1.0f, 1, 0, 1.0f); } catch (Exception ignored) {}
        } else {
            // Clip still loading (board opened fast) — retry once shortly.
            boardSoundHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    Integer retryId = boardSoundIds.get(clip);
                    Boolean retryLoaded = boardSoundLoaded.get(clip);
                    if (boardSounds != null && retryId != null && retryLoaded != null && retryLoaded) {
                        try { boardSounds.play(retryId, 1.0f, 1.0f, 1, 0, 1.0f); } catch (Exception ignored) {}
                    }
                }
            }, 200);
        }
    }

    // Open the board with this alarm's habit (once — onPageFinished can
    // fire again on a reload; the 1500ms safety re-call in onCreate
    // covers a missed call, so a second call here is harmless but skipped).
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
        // v5.7.15: the board's sounds, played natively (SoundPool).
        @JavascriptInterface
        public void playBoardSound(final String clip) {
            playBoardSoundImpl(clip);
        }

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
        // Message mode: just dismiss (and stop any alarm loop that is
        // running) and close the window — the user is already in
        // MainActivity, so there is no quest decision to apply.
        if (messageMode) {
            QuestReceiver.dismissAlarm();
            finish();
            return;
        }
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
        boardSoundHandler.removeCallbacksAndMessages(null);
        if (boardSounds != null) {
            try { boardSounds.release(); } catch (Exception ignored) {}
            boardSounds = null;
        }
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
