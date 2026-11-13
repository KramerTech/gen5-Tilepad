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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The full-screen window Package Manager's install shows until the head unit restarts, so nobody
 * opens apps mid-install or wonders whether anything is happening. SelfUpdate.script starts it as
 * its own root process, from its own copy of this dex, so updating Tilepad doesn't stop it. It reads
 * the install's progress from result.properties (SelfUpdate.result). The install doesn't depend on it.
 * Tweaks shows the same {@link Panel} while it downloads, until this one is up (SelfUpdate.SCREEN_SHOWN).
 */
public final class InstallScreen {
    /** How long after the last install the head unit should have restarted. */
    private static final long RESTART_MS = 90 * 1000L;
    public static final int INK = 0xffe6ebee, MUTED = 0xffb6c1c8, PROBLEM = 0xffe0785c, DONE = 0xff34d27b;
    private final File dir;
    private final String boot;
    private final Handler handler = new Handler();
    private final WindowManager windows;
    private final Panel panel;
    private long restartingSince;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        new InstallScreen(context, new File(args[0])).show();
        Looper.loop();
    }
    private InstallScreen(Context context, File dir) throws Exception {
        this.dir = dir; boot = Platform.boot();
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        panel = new Panel(context, new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
    }
    private void show() throws Exception {
        refresh();
        windows.addView(panel.view, Panel.params());
        Files.write(new File(dir, SelfUpdate.SCREEN_SHOWN), boot.getBytes("UTF-8"));
    }

    private final Runnable poll = new Runnable() { @Override public void run() { refresh(); } };
    private void refresh() {
        long now = System.currentTimeMillis();
        Properties r = SelfUpdate.result(dir, SelfUpdate.APPS, boot, now);
        String[] ids = r.getProperty("update.items", "").split(",");
        boolean installing = "installing".equals(r.getProperty("update.state")), fresh = true;
        int current = -1, installed = 0;
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String p = "update." + ids[i] + ".", state = r.getProperty(p + "state", "");
            fresh &= r.getProperty(p + "from", "").isEmpty();
            String status;
            int color = MUTED;
            if (state.equals("installing")) {
                if (current < 0) { current = i; status = "Installing…"; color = INK; } else status = "Waiting";
            } else if (state.equals("installed")) { status = "Installed"; color = DONE; installed++; }
            else { status = "Didn't install"; color = PROBLEM; }
            rows.add(new Object[]{r.getProperty(p + "name", ids[i]) + " " + r.getProperty(p + "version", ""), status, color});
        }
        if (installing && current >= 0) {
            panel.show(Panel.title(fresh), "Installing " + r.getProperty("update." + ids[current] + ".name", ids[current])
                + Panel.of(current, ids.length) + ". " + Panel.RESTART_NOTE, rows, false);
        } else if (installed > 0) {
            if (restartingSince == 0) restartingSince = now;
            if (now - restartingSince < RESTART_MS) panel.show("Restarting", "Restarting the head unit to finish the install.", rows, false);
            else panel.show("Restart Needed", "The head unit didn't restart on its own. Tap “Close”, then turn the car off and on again to finish the install.", rows, true);
        } else {
            panel.show("Nothing Was Installed", (ids.length > 1 ? "None of the updates installed." : r.getProperty("update." + ids[0] + ".name", ids[0]) + " didn't install.")
                + " Tap “Close”, then open Package Manager to see why.", rows, true);
            return;
        }
        handler.postDelayed(poll, 1000);
    }
    private void finish() {
        handler.removeCallbacks(poll);
        try { windows.removeView(panel.view); } catch (Exception ignored) { }
        handler.postDelayed(new Runnable() { @Override public void run() { System.exit(0); } }, 300);
    }

    /** The screen's layout: a spinner, a title, what's happening, a row per package, and Close once it's over. */
    public static final class Panel {
        public static final String RESTART_NOTE = "The head unit will restart on its own when installation is complete, so keep the car on until then.";
        public final FrameLayout view;
        private final Context context;
        private final ProgressBar spinner;
        private final TextView title, body, close;
        private final LinearLayout rows;

        public Panel(Context base, View.OnClickListener onClose) {
            // The same theme, and so the same spinner, as Tweaks; the system context's default theme draws Hyundai's.
            context = new ContextThemeWrapper(base, android.R.style.Theme_Holo_NoActionBar);
            // Swallows every key it's given; Android doesn't send Home to a focused system alert.
            view = new FrameLayout(context) {
                @Override public boolean dispatchKeyEvent(KeyEvent event) { return true; }
            };
            view.setBackgroundColor(0xff171b1f);
            LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            spinner = new ProgressBar(context); spinner.setIndeterminate(true);
            column.addView(spinner, new LinearLayout.LayoutParams(dp(64), dp(64)));
            title = text(34, INK); title.setGravity(Gravity.CENTER_HORIZONTAL); title.setPadding(0, dp(22), 0, dp(10)); column.addView(title);
            body = text(22, MUTED); body.setGravity(Gravity.CENTER_HORIZONTAL); body.setLineSpacing(dp(3), 1f); column.addView(body);
            rows = new LinearLayout(context); rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(0, dp(18), 0, 0);
            column.addView(rows, new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));
            close = text(26, INK); close.setText("Close"); close.setGravity(Gravity.CENTER); close.setVisibility(View.GONE);
            StateListDrawable button = new StateListDrawable();
            button.addState(new int[]{android.R.attr.state_pressed}, rounded(0xff101315)); button.addState(new int[]{}, rounded(0xff2c3238));
            close.setBackground(button);
            close.setOnClickListener(onClose);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(280), dp(72)); at.topMargin = dp(26);
            column.addView(close, at);
            view.addView(column, new FrameLayout.LayoutParams(dp(900), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        /** Full screen, above apps and the status bar (layers 2 and 15 on this firmware), below the reverse camera (20). */
        public static WindowManager.LayoutParams params() {
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
            p.setTitle("Tilepad install");
            return p;
        }
        public static String title(boolean fresh) { return fresh ? "Installing Software" : "Installing Updates"; }
        /** " (2 of 3)", or nothing for one package. */
        public static String of(int index, int count) { return count > 1 ? " (" + (index + 1) + " of " + count + ")" : ""; }
        /** Each row is {name and version, status, status color}. Finished hides the spinner and offers Close. */
        public void show(String heading, String message, List<Object[]> list, boolean finished) {
            title.setText(heading); body.setText(message);
            rows.removeAllViews();
            for (Object[] r : list) {
                LinearLayout row = new LinearLayout(context); row.setPadding(0, dp(6), 0, dp(6));
                TextView left = text(22, INK); left.setText((String) r[0]);
                TextView right = text(22, (Integer) r[2]); right.setText((String) r[1]); right.setGravity(Gravity.RIGHT);
                row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                rows.addView(row);
            }
            spinner.setVisibility(finished ? View.INVISIBLE : View.VISIBLE);
            if (finished && close.getVisibility() != View.VISIBLE) { close.setVisibility(View.VISIBLE); close.requestFocus(); }
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
}
