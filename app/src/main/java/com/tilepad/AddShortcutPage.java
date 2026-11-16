package com.tilepad;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen picker over every exported MAIN activity (including Hyundai screens with no
 * launcher icon), filtered by the built-in keyboard. Tapping an entry adds it as a tile.
 */
final class AddShortcutPage extends LinearLayout {
    interface Listener {
        void onPick(AppCatalog.Entry entry, boolean launcher);
        void onClose();
        boolean isAdded(String component);
    }

    private final Ui ui;
    private final Listener listener;
    private final TextView query, empty;
    private final View clear, searchBox, keypad;
    private final TextView appsTab, allTab;
    private final View back;
    private final GridView grid;
    private final Adapter adapter = new Adapter();
    private AppCatalog catalog;
    private boolean appsOnly;
    private String text = "";
    private int knobIndex = -1;

    AddShortcutPage(Ui ui, Listener listener) {
        super(ui.context);
        this.ui = ui;
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        setClickable(true); // keep touches from reaching the grid underneath

        LinearLayout bar = ui.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        back = ui.iconButton(Glyph.Kind.BACK, Ui.SURFACE, Ui.SURFACE_PRESSED, Ui.TEXT, 56, 28);
        back.setContentDescription("Back");
        click(back, new Runnable() { @Override public void run() { onBack(); } });
        bar.addView(back, new LayoutParams(ui.dp(56), ui.dp(56)));

        LinearLayout box = ui.row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(ui.dp(18), 0, ui.dp(6), 0);
        searchBox = box;
        box.addView(ui.glyph(Glyph.Kind.SEARCH, Ui.MUTED, 24), new LayoutParams(ui.dp(24), ui.dp(24)));
        query = ui.line("", 20, Ui.TEXT, false);
        LayoutParams qp = new LayoutParams(0, -2, 1f);
        qp.leftMargin = ui.dp(14);
        box.addView(query, qp);
        clear = ui.iconButton(Glyph.Kind.CLOSE, Ui.SURFACE_PRESSED, Ui.LINE, Ui.TEXT, 40, 20);
        clear.setContentDescription("Clear search");
        click(clear, new Runnable() { @Override public void run() { setQuery(""); } });
        box.addView(clear, new LayoutParams(ui.dp(40), ui.dp(40)));
        click(box, new Runnable() { @Override public void run() { setKeypadVisible(true); } });
        LayoutParams bp = new LayoutParams(0, ui.dp(56), 1f);
        bp.leftMargin = ui.dp(16);
        bar.addView(box, bp);

        appsTab = tab("Apps", true);
        allTab = tab("All screens", false);
        LinearLayout tabs = ui.row();
        tabs.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        Ui.background(tabs, ui.rounded(Ui.TILE, 28));
        tabs.addView(appsTab, new LayoutParams(ui.dp(120), -1));
        tabs.addView(allTab, new LayoutParams(ui.dp(150), -1));
        LayoutParams tp = new LayoutParams(-2, ui.dp(56));
        tp.leftMargin = ui.dp(16);
        bar.addView(tabs, tp);
        addView(bar, new LayoutParams(-1, ui.dp(56)));

        LinearLayout body = ui.row();
        FrameLayout results = new FrameLayout(ui.context);
        grid = new GridView(ui.context);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(ui.dp(300));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(ui.dp(12));
        grid.setVerticalSpacing(ui.dp(12));
        grid.setSelector(android.R.color.transparent);
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setVerticalFadingEdgeEnabled(true);
        grid.setFadingEdgeLength(ui.dp(24));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                pick(adapter.getItem(position));
            }
        });
        results.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = ui.text("Loading screens…", 20, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        results.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        body.addView(results, new LayoutParams(0, -1, 1f));

        keypad = new KeyPad(ui, "Hide", new KeyPad.Listener() {
            @Override public void onText(String value) { setQuery(text + value); }
            @Override public void onBackspace() { if (!text.isEmpty()) setQuery(text.substring(0, text.length() - 1)); }
            @Override public void onClear() { setQuery(""); }
            @Override public void onDone() { setKeypadVisible(false); }
        });
        keypad.setVisibility(GONE);
        LayoutParams kp = new LayoutParams(ui.dp(560), -1);
        kp.leftMargin = ui.dp(16);
        body.addView(keypad, kp);
        LayoutParams bodyParams = new LayoutParams(-1, 0, 1f);
        bodyParams.topMargin = ui.dp(16);
        addView(body, bodyParams);

        selectTab(false);
        setQuery("");
        setCatalog(AppCatalog.cached());
    }

    void setCatalog(AppCatalog value) {
        catalog = value;
        refresh();
    }

    /** Back closes the keyboard first, then the page. */
    void onBack() {
        if (keypad.getVisibility() == VISIBLE) setKeypadVisible(false);
        else listener.onClose();
    }

    private TextView tab(String label, final boolean apps) {
        TextView v = ui.text(label, 17, Ui.TEXT, false);
        v.setGravity(Gravity.CENTER);
        click(v, new Runnable() { @Override public void run() { selectTab(apps); } });
        return v;
    }

    private void selectTab(boolean apps) {
        appsOnly = apps;
        for (TextView tab : new TextView[]{appsTab, allTab}) {
            boolean selected = (tab == appsTab) == apps;
            Ui.background(tab, ui.pressable(selected ? Ui.ACCENT : Ui.TILE, selected ? Ui.ACCENT : Ui.TILE_PRESSED, 24));
            tab.setTextColor(selected ? Ui.TEXT : Ui.MUTED);
        }
        refresh();
    }

    private void setKeypadVisible(boolean visible) {
        keypad.setVisibility(visible ? VISIBLE : GONE);
        Ui.background(searchBox, visible ? ui.outlined(Ui.SURFACE, 28, 2, Ui.ACCENT)
            : ui.pressable(Ui.SURFACE, Ui.SURFACE_PRESSED, 28));
    }

    private void setQuery(String value) {
        text = value;
        query.setText(value.isEmpty() ? "Search screens" : value);
        query.setTextColor(value.isEmpty() ? Ui.MUTED : Ui.TEXT);
        clear.setVisibility(value.isEmpty() ? INVISIBLE : VISIBLE);
        if (keypad != null) setKeypadVisible(keypad.getVisibility() == VISIBLE);
        else Ui.background(searchBox, ui.pressable(Ui.SURFACE, Ui.SURFACE_PRESSED, 28));
        refresh();
    }

    private void refresh() {
        // Bucket by rank so label hits come before package-name hits; each bucket stays alphabetical.
        List<List<AppCatalog.Entry>> ranked = new ArrayList<>();
        for (int i = 0; i < 3; i++) ranked.add(new ArrayList<AppCatalog.Entry>());
        if (catalog != null) {
            for (AppCatalog.Entry e : appsOnly ? catalog.apps : catalog.all) {
                if (SearchFilter.matches(text, e.label, e.key)) ranked.get(SearchFilter.rank(text, e.label)).add(e);
            }
        }
        List<AppCatalog.Entry> matches = new ArrayList<>();
        for (List<AppCatalog.Entry> bucket : ranked) matches.addAll(bucket);
        adapter.set(matches);
        empty.setText(catalog == null ? "Loading screens…" : text.isEmpty() ? "No screens found" : "Nothing matches “" + text + "”");
        empty.setVisibility(matches.isEmpty() ? VISIBLE : GONE);
        knobIndex = -1;
        adapter.notifyDataSetChanged();
    }

    private void pick(AppCatalog.Entry entry) {
        if (entry == null || !entry.launchable || listener.isAdded(entry.key)) return;
        listener.onPick(entry, catalog != null && catalog.apps.contains(entry));
    }

    // ---- TUNE knob: back, the two tabs, then every result ----

    void knob(int delta) {
        int count = 3 + adapter.getCount();
        boolean[] enabled = new boolean[count];
        enabled[0] = enabled[1] = enabled[2] = true;
        for (int i = 0; i < adapter.getCount(); i++) enabled[3 + i] = selectable(adapter.getItem(i));
        knobIndex = KnobFocus.next(knobIndex, delta, enabled);
        back.setActivated(knobIndex == 0);
        appsTab.setActivated(knobIndex == 1);
        allTab.setActivated(knobIndex == 2);
        if (knobIndex >= 3) grid.smoothScrollToPosition(knobIndex - 3);
        adapter.notifyDataSetChanged();
    }

    void knobEnter() {
        if (knobIndex == 0) onBack();
        else if (knobIndex == 1) selectTab(true);
        else if (knobIndex == 2) selectTab(false);
        else if (knobIndex >= 3 && knobIndex - 3 < adapter.getCount()) pick(adapter.getItem(knobIndex - 3));
        else knob(1);
    }

    private boolean selectable(AppCatalog.Entry e) { return e.launchable && !listener.isAdded(e.key); }

    private static void click(View view, final Runnable action) {
        view.setClickable(true);
        view.setFocusable(false);
        view.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { action.run(); } });
    }

    private final class Adapter extends BaseAdapter {
        private final List<AppCatalog.Entry> items = new ArrayList<>();

        void set(List<AppCatalog.Entry> entries) { items.clear(); items.addAll(entries); }

        @Override public int getCount() { return items.size(); }
        @Override public AppCatalog.Entry getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row = convertView instanceof Row ? (Row) convertView : new Row();
            row.bind(items.get(position), position + 3 == knobIndex);
            return row;
        }
    }

    private final class Row extends LinearLayout {
        final ImageView icon;
        final TextView label, component;
        final ImageView state;

        Row() {
            super(ui.context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(16), 0, ui.dp(16), 0);
            setMinimumHeight(ui.dp(80));
            setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, ui.dp(80)));
            Ui.background(this, ui.pressable(Ui.TILE, Ui.TILE_PRESSED, 18));
            icon = new ImageView(ui.context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(icon, new LayoutParams(ui.dp(48), ui.dp(48)));
            LinearLayout text = ui.column();
            label = ui.line("", 18, Ui.TEXT, true);
            component = ui.line("", 13, Ui.MUTED, false);
            text.addView(label);
            LayoutParams cp = new LayoutParams(-1, -2);
            cp.topMargin = ui.dp(6);
            text.addView(component, cp);
            LayoutParams tp = new LayoutParams(0, -2, 1f);
            tp.leftMargin = ui.dp(16);
            tp.rightMargin = ui.dp(10);
            addView(text, tp);
            state = new ImageView(ui.context);
            addView(state, new LayoutParams(ui.dp(26), ui.dp(26)));
        }

        void bind(AppCatalog.Entry e, boolean knob) {
            boolean added = listener.isAdded(e.key);
            icon.setImageDrawable(e.icon);
            label.setText(e.label);
            component.setText(e.launchable ? e.key : "Protected · can't be opened");
            state.setImageDrawable(new Glyph(added ? Glyph.Kind.CHECK : Glyph.Kind.PLUS,
                added ? Ui.GREEN : Ui.ACCENT, ui.dp(26)));
            state.setVisibility(e.launchable ? VISIBLE : INVISIBLE);
            setAlpha(e.launchable ? 1f : 0.4f);
            setActivated(knob);
            setContentDescription(e.label + (added ? ". Already added" : ""));
        }
    }
}
