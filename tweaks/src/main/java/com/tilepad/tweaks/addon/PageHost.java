package com.tilepad.tweaks.addon;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** What the package pages need from the screen that shows them: its views, style and main thread. */
public interface PageHost {
    /** The screen, for views and dialogs, and whether it's closing. */
    Activity activity();
    /** The middle column the selected entry draws into. */
    LinearLayout controls();
    int dp(float n);
    int ink();
    int muted();
    /** The settings have loaded. */
    boolean ready();
    /** A read or save is running that greys the controls. */
    boolean busy();
    Handler main();
    /** A line of text in the screen's style. */
    TextView text(String label, float size, int color);
    /** A note in the middle column: smaller and red for a problem. */
    TextView note(String label, boolean problem);
    /** A button in the screen's style, enabled while ready and not busy. */
    Button button(String label, String description, View.OnClickListener click);
    /** Adds an action button to the middle column, inset and sized like the tweaks' own. */
    void action(Button b);
    /** Asks before a step, in the screen's own dialog style; then runs on confirm. */
    void confirm(String heading, String message, String confirm, Runnable then);
    /** Says what happened, in a window in the screen's own dialog style. */
    void tell(String heading, String message);
    /** Shows body (something to pick from) in a window in the screen's own dialog style, with Cancel. */
    android.app.Dialog choose(String heading, View body);
    void divider(LinearLayout parent);
    /** One of the stock EV Settings drawables, or fallback without them. */
    Drawable stockDrawable(String name, Drawable fallback);
    /** A list row's pressed and focused highlight. */
    Drawable selector(boolean selected);
    /** Redraws the whole window, the left pane included. */
    void render();
    /** Redraws only the middle column and the header's state word, keeping the scroll position. */
    void renderInPlace();
}
