package com.kader.systemapp;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v5.7.12: full-screen System quest window (boss, 2026-11-10 — "like the
 * photo, full screen like the video, ACCEPT and DECLINE below").
 * When a quest alarm fires, the notification's full-screen intent opens
 * THIS activity instead of MainActivity: a dark screen with the glowing
 * cyan holographic System frame, a NOTIFICATION header, the quest name
 * + time, "Will you accept?", and ACCEPT / DECLINE buttons BELOW the
 * window — exactly like the anime's Yes/No prompt in the video.
 * ACCEPT/DECLINE are final for the day: the tap stops the alarm loop and
 * opens MainActivity, which applies the decision in the web app
 * (quest_decision extra -> window.decideQuest; the web app guards
 * one-tap-only, so a repeated call is a no-op). 100% English, zero
 * Amharic — the app rule. The WAKEY alarm loop is untouched: it keeps
 * looping until ACCEPT/DECLINE (or notification tap/swipe).
 * Back closes the window only — the alarm keeps ringing until stopped.
 */
public class QuestAlarmActivity extends Activity {

    private static final int CYAN = 0xFF00F5FF;
    private static final int BG = 0xFF020814;
    private static final int PANEL_BG = 0xFF061224;
    private static final int RED = 0xFFFF4D6D;

    private String questId = "quest";
    private String questTitle = "Quest time is here";
    private String questTime = "";
    private boolean saitama = false;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
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

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(content, contentLp);

        // Outer glow frame (the holographic double-frame of the anime window).
        LinearLayout glow = new LinearLayout(this);
        glow.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glowBg = new GradientDrawable();
        glowBg.setColor(0xFF03182B);
        glowBg.setStroke(dp(1), Color.argb(102, 0, 245, 255));
        glowBg.setCornerRadius(dp(2));
        glow.setBackground(glowBg);
        glow.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams glowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        content.addView(glow, glowLp);

        // Inner System panel.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(PANEL_BG);
        panelBg.setStroke(dp(2), CYAN);
        panelBg.setCornerRadius(dp(2));
        panel.setBackground(panelBg);
        panel.setPadding(dp(20), dp(24), dp(20), dp(24));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        glow.addView(panel, panelLp);

        // Header: NOTIFICATION.
        TextView header = new TextView(this);
        header.setText("\u26A0 NOTIFICATION");
        header.setTextColor(CYAN);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            header.setLetterSpacing(0.25f);
        }
        header.setGravity(Gravity.CENTER);
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Divider line.
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(128, 0, 245, 255));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(16), 0, dp(16));
        panel.addView(divider, divLp);

        // Quest name.
        TextView name = new TextView(this);
        name.setText(questTitle);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        panel.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Quest time.
        if (questTime != null && !questTime.isEmpty()) {
            TextView time = new TextView(this);
            time.setText(questTime);
            time.setTextColor(0xFF7FD4FF);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            time.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            timeLp.setMargins(0, dp(8), 0, 0);
            panel.addView(time, timeLp);
        }

        // The anime's question, like the video's "Will you accept?".
        TextView ask = new TextView(this);
        ask.setText("Will you accept?");
        ask.setTextColor(0xFFCFE9FF);
        ask.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ask.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams askLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        askLp.setMargins(0, dp(18), 0, 0);
        panel.addView(ask, askLp);

        // ACCEPT / DECLINE — BELOW the window, like Yes/No in the video.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.setMargins(0, dp(20), 0, 0);
        content.addView(buttons, btnRowLp);

        Button accept = new Button(this);
        accept.setText("ACCEPT");
        accept.setTextColor(BG);
        accept.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        accept.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable acceptBg = new GradientDrawable();
        acceptBg.setColor(CYAN);
        acceptBg.setCornerRadius(dp(2));
        accept.setBackground(acceptBg);
        accept.setPadding(0, dp(16), 0, dp(16));
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        acceptLp.setMargins(0, 0, dp(6), 0);
        buttons.addView(accept, acceptLp);
        accept.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { decide("done"); }
        });

        Button decline = new Button(this);
        decline.setText("DECLINE");
        decline.setTextColor(RED);
        decline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        decline.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable declineBg = new GradientDrawable();
        declineBg.setColor(Color.TRANSPARENT);
        declineBg.setStroke(dp(2), RED);
        declineBg.setCornerRadius(dp(2));
        decline.setBackground(declineBg);
        decline.setPadding(0, dp(16), 0, dp(16));
        LinearLayout.LayoutParams declineLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        declineLp.setMargins(dp(6), 0, 0, 0);
        buttons.addView(decline, declineLp);
        decline.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { decide("missed"); }
        });
    }

    // ACCEPT ("done") / DECLINE ("missed"): stop the alarm loop, clear
    // the notification, and open the app, which applies the decision in
    // the web app (quest_decision extra). One tap only — decideQuest in
    // the web app ignores a second decision for the same quest today.
    private void decide(String act) {
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
}
