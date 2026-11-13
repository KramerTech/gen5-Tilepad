package com.tilepad.tweakcore;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.Properties;

/**
 * The full-screen window Package Manager's install shows until the head unit restarts, so nobody
 * opens apps mid-install or wonders whether anything is happening. SelfUpdate.script starts it as
 * its own root process, from its own copy of this dex, so updating Tilepad doesn't stop it. It reads
 * the install's progress from result.properties (SelfUpdate.result). The install doesn't depend on it.
 */
public final class InstallScreen {
    /** How long after the last install the head unit should have restarted. */
    private static final long RESTART_MS = 90 * 1000L;
    private static final int BG = 0xff171b1f, INK = 0xffe6ebee, MUTED = 0xffb6c1c8, PROBLEM = 0xffe0785c, DONE = 0xff34d27b;
    private final Context context;
    private final File dir;
    private final String boot;
    private final Handler handler = new Handler();
    private final WindowManager windows;
    private FrameLayout root;
    private ProgressBar spinner;
    private TextView title, body;
    private LinearLayout rows;
    private TextView close;
    private long restartingSince;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        new InstallScreen(context, new File(args[0])).show();
        Looper.loop();
    }
    private InstallScreen(Context context, File dir) throws Exception {
        // The same theme, and so the same spinner, as Tweaks; the system context's default theme draws Hyundai's.
        this.context = new ContextThemeWrapper(context, android.R.style.Theme_Holo_NoActionBar); this.dir = dir; boot = Platform.boot();
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    private void show() {
        // Swallows every key it's given; Android doesn't send Home to a focused system alert.
        root = new FrameLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) { return true; }
        };
        root.setBackgroundColor(BG);
        LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        spinner = new ProgressBar(this.context); spinner.setIndeterminate(true);
        column.addView(spinner, new LinearLayout.LayoutParams(dp(64), dp(64)));
        title = text(34, INK); title.setGravity(Gravity.CENTER_HORIZONTAL); title.setPadding(0, dp(22), 0, dp(10)); column.addView(title);
        body = text(22, MUTED); body.setGravity(Gravity.CENTER_HORIZONTAL); body.setLineSpacing(dp(3), 1f); column.addView(body);
        rows = new LinearLayout(context); rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(0, dp(18), 0, 0);
        column.addView(rows, new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));
        close = text(26, INK); close.setText("Close"); close.setGravity(Gravity.CENTER); close.setVisibility(View.GONE);
        StateListDrawable button = new StateListDrawable();
        button.addState(new int[]{android.R.attr.state_pressed}, rounded(0xff101315)); button.addState(new int[]{}, rounded(0xff2c3238));
        close.setBackground(button);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(280), dp(72)); at.topMargin = dp(26);
        column.addView(close, at);
        root.addView(column, new FrameLayout.LayoutParams(dp(900), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Above apps and the status bar (layers 2 and 15 on this firmware), below the reverse camera (20).
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE);
        p.setTitle("Tilepad install");
        refresh();
        windows.addView(root, p);
    }

    private final Runnable poll = new Runnable() { @Override public void run() { refresh(); } };
    private void refresh() {
        long now = System.currentTimeMillis();
        Properties r = SelfUpdate.result(dir, SelfUpdate.APPS, boot, now);
        String[] ids = r.getProperty("update.items", "").split(",");
        boolean installing = "installing".equals(r.getProperty("update.state"));
        int current = -1, installed = 0;
        rows.removeAllViews();
        for (int i = 0; i < ids.length; i++) {
            String p = "update." + ids[i] + ".", state = r.getProperty(p + "state", "");
            String status;
            int color = MUTED;
            if (state.equals("installing")) {
                if (current < 0) { current = i; status = "Installing…"; color = INK; } else status = "Waiting";
            } else if (state.equals("installed")) { status = "Installed"; color = DONE; installed++; }
            else { status = "Didn't install"; color = PROBLEM; }
            rows.addView(row(r.getProperty(p + "name", ids[i]) + " " + r.getProperty(p + "version", ""), status, color));
        }
        if (installing && current >= 0) {
            String p = "update." + ids[current] + ".";
            title.setText("Installing Updates");
            body.setText("Installing " + r.getProperty(p + "name", ids[current]) + (ids.length > 1 ? " (" + (current + 1) + " of " + ids.length + ")" : "")
                + ". The head unit will restart on its own when installation is complete, so keep the car on until then.");
        } else if (installed > 0) {
            if (restartingSince == 0) restartingSince = now;
            if (now - restartingSince < RESTART_MS) { title.setText("Restarting"); body.setText("Restarting the head unit to finish the install."); }
            else { title.setText("Restart Needed"); body.setText("The head unit didn't restart on its own. Tap “Close”, then turn the car off and on again to finish the install."); stop(); }
        } else {
            title.setText("Nothing Was Installed");
            body.setText(ids.length > 1 ? "None of the updates installed. Tap “Close”, then open Package Manager to see why."
                : "The update didn't install. Tap “Close”, then open Package Manager to see why.");
            stop();
            return;
        }
        handler.postDelayed(poll, 1000);
    }
    /** Nothing more to wait for: offer the way out. */
    private void stop() {
        spinner.setVisibility(View.INVISIBLE);
        if (close.getVisibility() != View.VISIBLE) { close.setVisibility(View.VISIBLE); close.requestFocus(); }
    }
    private void finish() {
        handler.removeCallbacks(poll);
        try { windows.removeView(root); } catch (Exception ignored) { }
        handler.postDelayed(new Runnable() { @Override public void run() { System.exit(0); } }, 300);
    }

    private View row(String name, String status, int color) {
        LinearLayout row = new LinearLayout(context); row.setPadding(0, dp(6), 0, dp(6));
        TextView left = text(22, INK); left.setText(name);
        TextView right = text(22, color); right.setText(status); right.setGravity(Gravity.RIGHT);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }
    private TextView text(float size, int color) {
        TextView v = new TextView(context); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(8)); return d;
    }
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.getResources().getDisplayMetrics()));
    }
}
