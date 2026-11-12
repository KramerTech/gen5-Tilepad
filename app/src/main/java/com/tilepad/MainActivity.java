package com.tilepad;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Grid launcher: square tiles, long-press to rearrange, steering-wheel star hold to open or close. */
public final class MainActivity extends Activity implements TileGrid.Listener, TileView.EditListener, Sheet.Listener {
    static final String EXTRA_FROM_KNOB = "from_knob";
    private static final String TAG = "Tilepad";
    private static final String NEMESIS_COMPONENT = "com.nemesis.manager/.MainActivity";
    /** Root-side half of "turn on the Wi-Fi client", shipped in the NEMESIS package. */
    private static final String WIFI_CLIENT_SCRIPT = "/data/nemesis/apps/tilepad/bin/wifi-client.sh";
    /** Turns ADB over TCP on or off (the firmware property, as root); shipped in the package. */
    private static final String ADB_TILE_SCRIPT = "/data/nemesis/apps/tilepad/bin/adb-tile.sh";
    private static final long WIFI_EXPECTED_MS = 30000;
    private static final String WIFI_CLIENT_XML = "/system/etc/permissions/android.hardware.wifi.xml";
    private static final int KEY_TUNE_UP = keyCode("KEYCODE_TUNE_UP", 223);
    private static final int KEY_TUNE_DOWN = keyCode("KEYCODE_TUNE_DOWN", 224);
    private static final int KEY_TUNE_ENTER = keyCode("KEYCODE_TUNE_ENTER", 243);

    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private FrameLayout root;
    private TileGrid grid;
    private View done;
    private Sheet sheet;
    private AddShortcutPage addPage;
    private SharedPreferences prefs;
    private List<TileSpec> specs;
    private TileSupport support;
    private WifiTile wifiTile;
    private PreconTile preconTile;
    private AdbTile adbTile;
    private TileGrid.Scroll scroll;
    /** A settings screen this opened is on top of it (see launch). */
    private boolean settingsOnTop;
    private TweaksTile tweaksTile;
    private TweaksSnapshot tweaks = TweaksSnapshot.checking();
    private boolean tweaksReading;
    private int tweaksEpoch;
    private boolean preview, resumed, started, receiverRegistered, connectionReading, enterDown, enterLong;
    private long resumedAt, enterAt;
    private int connectionEpoch, adbEpoch, sheetKnob = -1;
    private ConnectionSnapshot connection = ConnectionSnapshot.unknown();
    /** When the ADB tile started turning Wi-Fi on; until it connects the tile says to wait. */
    private long wifiExpectedSince;

    private AdbSnapshot adb = AdbSnapshot.checking();
    private boolean adbPendingOn;
    /** While a toggle is in flight, holds the Starting/Stopping display until the daemon catches up. */
    private long adbPendingSince;
    /** Why the last toggle's root command failed; shown only if adbd doesn't settle anyway. */
    private String adbPendingError;
    /** The ADB popup, while it is the sheet on screen. */
    private StatusCard adbCard;

    private PreconditioningService.LocalBinder vehicleService;
    private boolean vehicleBound;
    private PreconditioningSnapshot preconditioning = PreconditioningSnapshot.checking();
    private PreconditioningSnapshot.State lastPreconState;
    /** The precondition popup, while it is the sheet on screen. */
    private StatusCard preconCard;
    private long awaitingStartAt;
    private boolean stopIssued, startFailed, preconAutoClose;

    private final ServiceConnection vehicleConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            vehicleService = (PreconditioningService.LocalBinder) binder;
            preconTick.run();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            vehicleService = null;
            onPreconditioning(PreconditioningSnapshot.checking());
        }
    };
    private final Runnable preconTick = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!resumed) return;
            if (vehicleService != null) onPreconditioning(vehicleService.snapshot());
            else if (preconCard != null) renderPreconCard();
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable connectionTick = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!resumed) return;
            refreshConnection();
            main.postDelayed(this, 3000);
        }
    };
    private final Runnable adbTick = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!resumed) return;
            refreshAdb();
            main.postDelayed(this, 3000);
        }
    };
    private final BroadcastReceiver wifiChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (resumed) refreshConnection(); }
    };

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        preview = PreviewData.enabled(getIntent());
        support = preview ? TileSupport.all() : TileSupport.of(systemFeatures(), VehicleClientFactory.simulated());
        prefs = getSharedPreferences("tilepad", MODE_PRIVATE);
        specs = TileOrder.parse(prefs.getString("tiles", null));

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        grid = new TileGrid(this, ui);
        grid.setListener(this);
        scroll = new TileGrid.Scroll(this, grid, ui);
        applyInsets();
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        done = ui.button("Done", Glyph.Kind.CHECK, Ui.ACCENT, 0xff3a74d9, Ui.TEXT);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { grid.setEditing(false); }
        });
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-2, ui.dp(56), Gravity.BOTTOM | Gravity.RIGHT);
        dp.rightMargin = ui.dp(76);
        dp.bottomMargin = ui.dp(24);
        done.setVisibility(View.GONE);
        root.addView(done, dp);

        sheet = new Sheet(ui, root, this);
        setContentView(root);
        rebuildTiles();

        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final AppCatalog catalog = AppCatalog.load(app);
                main.post(new Runnable() { @Override public void run() { onCatalog(catalog); } });
            }
        }, "tilepad-catalog").start();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        // No preconditioning tile (gas car, unit without the EV option): never talk to the vehicle.
        if (support.precondition) vehicleBound = bindService(new Intent(this, PreconditioningService.class), vehicleConnection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        started = false;
        // Like noHistory: Tilepad closes once something covers it, except a settings screen it
        // opened itself, which Back returns from.
        if (!settingsOnTop) finish();
        if (vehicleBound) { unbindService(vehicleConnection); vehicleBound = false; }
        vehicleService = null;
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        settingsOnTop = false;
        resumedAt = SystemClock.uptimeMillis();
        if (support.wifi && !support.wifiClient) bindWifiClientOff();
        else if (support.wifi) {
            IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
            try { registerReceiver(wifiChanges, filter); receiverRegistered = true; }
            catch (SecurityException e) { receiverRegistered = false; }
            connectionTick.run();
        }
        refreshAvailability();
        if (support.precondition) preconTick.run();
        adbTick.run();
        refreshTweaks();
    }

    @Override protected void onPause() {
        resumed = false;
        connectionEpoch++;
        adbEpoch++;
        tweaksEpoch++;
        main.removeCallbacks(connectionTick);
        main.removeCallbacks(preconTick);
        main.removeCallbacks(adbTick);
        if (receiverRegistered) { unregisterReceiver(wifiChanges); receiverRegistered = false; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Holding the star again while Tilepad is showing closes it. Android pauses a resumed
        // activity before onNewIntent, so "started" is the visibility guard here.
        if (intent.getBooleanExtra(EXTRA_FROM_KNOB, false) && started
            && SystemClock.uptimeMillis() - resumedAt > 700) finish();
    }

    @Override public void onBackPressed() {
        if (sheet.isShowing()) sheet.dismiss();
        else if (addPage != null) addPage.onBack();
        else if (grid.isEditing()) grid.setEditing(false);
        else finish();
    }

    // ---- tiles ----

    private void rebuildTiles() {
        wifiTile = null;
        preconTile = null;
        adbTile = null;
        tweaksTile = null;
        List<TileView> views = new ArrayList<>();
        // Unsupported core tiles stay in the saved order, just not on the grid.
        for (TileSpec spec : specs) if (support.shows(spec.kind)) views.add(createTile(spec));
        grid.setTiles(views);
        refreshAvailability();
    }

    private TileView createTile(TileSpec spec) {
        final TileView tile;
        switch (spec.kind) {
            case WIFI:
                tile = wifiTile = new WifiTile(ui, spec, this);
                if (support.wifiClient) wifiTile.bind(connection);
                else wifiTile.bindClientOff(wifiClientPending());
                break;
            case ADB:
                tile = adbTile = new AdbTile(ui, spec, this);
                adbTile.bind(adb);
                break;
            case PRECONDITION:
                tile = preconTile = new PreconTile(ui, spec, this);
                preconTile.bind(preconditioning);
                break;
            case TWEAKS:
                tile = tweaksTile = new TweaksTile(ui, spec, this);
                tweaksTile.bind(tweaks);
                break;
            case NEMESIS:
                tile = TileView.simple(ui, spec, this, TileView.chip(ui, Glyph.Kind.TERMINAL, Ui.PURPLE), 80, "Nemesis Manager");
                break;
            case REBOOT:
                tile = TileView.simple(ui, spec, this, TileView.chip(ui, Glyph.Kind.REBOOT, Ui.RED), 80, "Reboot Head Unit");
                break;
            case ADD: {
                tile = TileView.simple(ui, spec, this, ui.glyph(Glyph.Kind.PLUS, Ui.MUTED, 56), 56, "Add shortcut");
                ((TextView) tile.findViewWithTag("label")).setTextColor(Ui.MUTED);
                break;
            }
            default:
                tile = TileView.simple(ui, spec, this, TileView.appIcon(ui, shortcutIcon(spec)), 80, shortcutLabel(spec));
        }
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!grid.isEditing()) onTileTap(tile); }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (sheet.isShowing() || addPage != null) return false;
                // The Wi-Fi tile never moves, so its long-press shows the radio's MAC address instead.
                if (tile.spec.kind == TileSpec.Kind.WIFI && !grid.isEditing()) { showWifiMac(); return true; }
                grid.beginDrag(tile);
                return true;
            }
        });
        return tile;
    }

    private Set<String> systemFeatures() {
        Set<String> names = new HashSet<>();
        FeatureInfo[] features = getPackageManager().getSystemAvailableFeatures();
        if (features != null) for (FeatureInfo feature : features) if (feature.name != null) names.add(feature.name);
        return names;
    }

    private void refreshAvailability() {
        if (grid == null) return;
        for (TileView tile : grid.tiles()) {
            Intent intent = intentFor(tile.spec);
            tile.setAvailable(intent == null || preview || canStart(intent));
        }
    }

    private void onTileTap(TileView tile) {
        TileSpec spec = tile.spec;
        switch (spec.kind) {
            case WIFI:
                if (!support.wifiClient) { offerWifiClient(); return; }
                launch(intentFor(spec), titleOf(tile), true);
                return;
            case ADB: onAdbTap(); return;
            case PRECONDITION: onPreconTap(); return;
            case REBOOT: confirmReboot(); return;
            case ADD: openAddPage(); return;
            default:
                Intent intent = intentFor(spec);
                if (intent != null) launch(intent, titleOf(tile), tile.spec.kind != TileSpec.Kind.SHORTCUT);
        }
    }

    private Intent intentFor(TileSpec spec) {
        switch (spec.kind) {
            case TWEAKS: return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.tilepad.tweaks", "com.tilepad.tweaks.TweaksActivity"));
            case WIFI: return new Intent("android.settings.WIFI_SETTINGS");
            case NEMESIS: return new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(NEMESIS_COMPONENT));
            case SHORTCUT: {
                ComponentName component = ComponentName.unflattenFromString(spec.component);
                if (component == null) return null;
                Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(component);
                if (spec.launcher) intent.addCategory(Intent.CATEGORY_LAUNCHER);
                return intent;
            }
            default: return null;
        }
    }

    private String titleOf(TileView tile) {
        switch (tile.spec.kind) {
            case TWEAKS: return "Tweaks and Settings";
            case WIFI: return "Wi-Fi settings";
            case NEMESIS: return "Nemesis Manager";
            default: return shortcutLabel(tile.spec);
        }
    }

    private boolean canStart(Intent intent) {
        ResolveInfo result = getPackageManager().resolveActivity(intent, 0);
        ActivityInfo info = result == null ? null : result.activityInfo;
        if (info == null || !info.exported || !info.enabled || !info.applicationInfo.enabled) return false;
        return info.permission == null
            || getPackageManager().checkPermission(info.permission, getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * A shortcut opens like from a launcher: in its own task, and Tilepad closes. A core tile's
     * settings screen (Wi-Fi, Tweaks, Nemesis Manager) opens on top of Tilepad, so Back returns here.
     */
    private void launch(Intent intent, String title, boolean returnHere) {
        if (!canStart(intent)) {
            showMessage(Glyph.Kind.ALERT, Ui.AMBER, "Can't open " + title,
                "It isn't installed or can't be opened on this head unit.");
            return;
        }
        if (!returnHere) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            if (returnHere) settingsOnTop = true;
            else finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            showMessage(Glyph.Kind.ALERT, Ui.RED, "Can't open " + title, e.getMessage());
        }
    }

    private ComponentName componentOf(TileSpec spec) {
        return spec.component == null ? null : ComponentName.unflattenFromString(spec.component);
    }

    private String shortcutLabel(TileSpec spec) {
        if (spec.label != null) return spec.label;
        // The activity's own label; the catalog's " · Class" disambiguation is for the picker list.
        try {
            ComponentName component = componentOf(spec);
            CharSequence label = getPackageManager().getActivityInfo(component, 0).loadLabel(getPackageManager());
            if (label != null && label.toString().trim().length() > 0) return label.toString().trim();
        } catch (Exception ignored) { }
        String component = spec.component == null ? "" : spec.component;
        return component.substring(component.lastIndexOf('.') + 1);
    }

    private Drawable shortcutIcon(TileSpec spec) {
        try { return getPackageManager().getActivityIcon(componentOf(spec)); }
        catch (Exception e) { return null; }
    }

    private void saveTiles() {
        prefs.edit().putString("tiles", TileOrder.serialize(specs)).apply();
    }

    private void onCatalog(AppCatalog catalog) {
        if (isFinishing()) return;
        if (addPage != null) addPage.setCatalog(catalog);
    }

    // ---- grid callbacks ----

    @Override public void onEditingChanged(boolean editing) {
        done.setVisibility(editing ? View.VISIBLE : View.GONE);
        if (editing) grid.setKnobIndex(-1);
    }

    @Override public void onOrderChanged(List<TileView> order) {
        List<String> ids = new ArrayList<>();
        for (TileView tile : order) ids.add(tile.spec.id);
        specs = TileOrder.reorder(specs, ids);
        saveTiles();
    }

    @Override public void onRemove(TileView tile) {
        String name = shortcutLabel(tile.spec);
        specs = TileOrder.remove(specs, tile.spec.id);
        saveTiles();
        rebuildTiles();
        toast("Removed " + name);
    }

    @Override public void onRename(final TileView tile) { showRename(tile.spec); }

    // ---- add shortcut ----

    private void openAddPage() {
        if (addPage != null) return;
        grid.setEditing(false);
        addPage = new AddShortcutPage(ui, new AddShortcutPage.Listener() {
            @Override public void onPick(AppCatalog.Entry entry, boolean launcher) {
                specs = TileOrder.add(specs, TileSpec.shortcut(entry.key, launcher, null));
                saveTiles();
                rebuildTiles();
                closeAddPage();
                toast("Added " + entry.label);
            }
            @Override public void onClose() { closeAddPage(); }
            @Override public boolean isAdded(String component) {
                return TileOrder.find(specs, TileSpec.shortcut(component, false, null).id) != null;
            }
        });
        addPage.setPadding(ui.dp(24), ui.dp(16), rightInset(), ui.dp(16));
        root.addView(addPage, root.indexOfChild(done) + 1, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeAddPage() {
        if (addPage == null) return;
        root.removeView(addPage);
        addPage = null;
    }

    // ---- rename ----

    private void showRename(final TileSpec spec) {
        final String original = shortcutLabel(spec.withLabel(null));
        final StringBuilder text = new StringBuilder(shortcutLabel(spec));
        LinearLayout card = ui.row();
        card.setPadding(ui.dp(32), ui.dp(26), ui.dp(26), ui.dp(26));

        LinearLayout left = ui.column();
        left.addView(ui.text("Rename shortcut", 26, Ui.TEXT, true));
        final TextView field = ui.line(text.toString(), 22, Ui.TEXT, false);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        Ui.background(field, ui.outlined(Ui.SURFACE, 16, 2, Ui.ACCENT));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, ui.dp(64));
        fp.topMargin = ui.dp(20);
        left.addView(field, fp);
        TextView hint = ui.text("Leave this empty to keep the original name, " + original + ".", 15, Ui.MUTED, false);
        hint.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = ui.dp(12);
        left.addView(hint, hp);
        left.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout buttons = ui.row();
        final View cancel = ui.button("Cancel", null, Ui.SURFACE, Ui.SURFACE_PRESSED, Ui.TEXT);
        final View save = ui.button("Save", null, Ui.ACCENT, 0xff3a74d9, Ui.TEXT);
        buttons.addView(cancel, new LinearLayout.LayoutParams(-2, ui.dp(58)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, ui.dp(58));
        sp.leftMargin = ui.dp(14);
        buttons.addView(save, sp);
        left.addView(buttons);
        card.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        final Runnable commit = new Runnable() {
            @Override public void run() {
                String value = text.toString().trim();
                specs = TileOrder.rename(specs, spec.id, value.equals(original) ? null : value);
                saveTiles();
                sheet.dismiss();
                rebuildTiles();
            }
        };
        KeyPad keys = new KeyPad(ui, "Save", new KeyPad.Listener() {
            @Override public void onText(String value) { text.append(value); field.setText(text); }
            @Override public void onBackspace() {
                if (text.length() > 0) text.setLength(text.length() - 1);
                field.setText(text);
            }
            @Override public void onClear() { text.setLength(0); field.setText(text); }
            @Override public void onDone() { commit.run(); }
        });
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ui.dp(560), ui.dp(268));
        kp.leftMargin = ui.dp(28);
        card.addView(keys, kp);

        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sheet.dismiss(); } });
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { commit.run(); } });
        sheet.show(card, 0, null);
        sheet.setTargets(Arrays.asList(cancel, save));
    }

    // ---- messages and reboot ----

    private void showMessage(Glyph.Kind kind, int color, String title, String body) {
        StatusCard card = new StatusCard(ui);
        card.set(kind, color, false, title, body, null);
        List<View> buttons = card.setActions(new StatusCard.Action("Close", Ui.SURFACE, dismiss()));
        sheet.show(card, 680, null);
        sheet.setTargets(buttons);
    }

    private Runnable dismiss() {
        return new Runnable() { @Override public void run() { sheet.dismiss(); } };
    }

    /** For reserving the car a static IP on a home router: the address the client radio joins networks with. */
    private void showWifiMac() {
        String mac = wifiMac();
        StatusCard card = new StatusCard(ui);
        if (mac == null) card.set(Glyph.Kind.WIFI_OFF, Ui.MUTED, false, "Wi-Fi MAC address",
            "The Wi-Fi radio hasn't reported its address. Turn the Wi-Fi client on and try again.", null);
        else card.set(Glyph.Kind.WIFI, Ui.ACCENT, false, "Wi-Fi MAC address", mac,
            "Use this to reserve a static IP address for the car on your router.");
        List<View> buttons = card.setActions(new StatusCard.Action("Close", Ui.SURFACE, dismiss()));
        sheet.show(card, 680, null);
        sheet.setTargets(buttons);
    }

    /** The framework's report, else the kernel's for wlan0, which is there even while the client is off. */
    private String wifiMac() {
        String mac = null;
        try {
            WifiInfo info = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            if (info != null) mac = info.getMacAddress();
        } catch (RuntimeException ignored) { }
        if (!realMac(mac)) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader("/sys/class/net/wlan0/address"))) {
                mac = in.readLine();
            } catch (java.io.IOException ignored) { mac = null; }
        }
        return realMac(mac) ? mac.trim().toUpperCase(java.util.Locale.US) : null;
    }

    private static boolean realMac(String mac) {
        return mac != null && mac.trim().matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") && !mac.trim().equals("00:00:00:00:00:00")
            && !mac.trim().equalsIgnoreCase("02:00:00:00:00:00");
    }

    private void confirmReboot() {
        final StatusCard card = new StatusCard(ui);
        String body = "The infotainment system restarts right away and is unavailable until it finishes starting up.";
        if (preconditioning.requested) body += " The preconditioning request will be cancelled first.";
        card.set(Glyph.Kind.REBOOT, Ui.RED, false, "Reboot head unit?", body, null);
        List<View> buttons = card.setActions(
            new StatusCard.Action("Cancel", Ui.SURFACE, dismiss()),
            new StatusCard.Action("Reboot", Ui.RED, new Runnable() { @Override public void run() { reboot(card); } }));
        sheet.show(card, 680, null);
        sheet.setTargets(buttons);
    }

    private void reboot(final StatusCard card) {
        card.set(null, Ui.RED, true, "Rebooting…", "Restarting the head unit.", null);
        sheet.setTargets(card.setActions());
        final boolean cancelFirst = preconditioning.requested;
        if (cancelFirst) send(PreconditioningService.STOP);
        new Thread(new Runnable() {
            @Override public void run() {
                // Give the service time to send the cancel sequence before the system goes down.
                if (cancelFirst) SystemClock.sleep(2500);
                final RootShell.Result result = RootShell.run("reboot");
                if (result.code == 0) SystemClock.sleep(15000); // a successful reboot never gets here
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    String detail = result.code == 0 ? "The reboot command was accepted, but the head unit is still running."
                        : (result.output.isEmpty() ? "nemesis exited with code " + result.code : result.output);
                    card.set(Glyph.Kind.ALERT, Ui.RED, false, "Reboot didn't happen", detail, null);
                    sheet.setTargets(card.setActions(new StatusCard.Action("Close", Ui.SURFACE, dismiss())));
                } });
            }
        }, "tilepad-reboot").start();
    }

    // ---- preconditioning ----

    private void send(String action) {
        startService(new Intent(this, PreconditioningService.class).setAction(action));
    }

    private void onPreconditioning(PreconditioningSnapshot s) {
        PreconditioningSnapshot.State previous = lastPreconState;
        preconditioning = s;
        lastPreconState = s.state;
        if (preconTile != null) preconTile.bind(s);
        if (preconCard != null) renderPreconCard();
        else if (previous == PreconditioningSnapshot.State.WAITING && s.state != previous
            && (s.state == PreconditioningSnapshot.State.ACTIVE || s.state == PreconditioningSnapshot.State.ERROR)
            && !sheet.isShowing() && addPage == null) {
            // The BMS answered while the popup was closed: pop up the result anyway.
            showPreconCard(s.state == PreconditioningSnapshot.State.ACTIVE);
        }
    }

    private void onPreconTap() {
        switch (PreconPresenter.tap(preconditioning)) {
            case START:
                send(PreconditioningService.START);
                awaitingStartAt = SystemClock.uptimeMillis();
                startFailed = false;
                stopIssued = false;
                showPreconCard(true);
                break;
            case STOP:
                send(PreconditioningService.STOP);
                stopIssued = true;
                showPreconCard(true);
                break;
            default:
                showPreconCard(false);
        }
    }

    private void showPreconCard(boolean autoClose) {
        final StatusCard card = new StatusCard(ui);
        // Keep the request state across show(): it dismisses any previous sheet first.
        long awaiting = awaitingStartAt;
        boolean stopping = stopIssued;
        sheet.show(card, 720, new Runnable() {
            @Override public void run() {
                if (preconCard != card) return;
                preconCard = null;
                awaitingStartAt = 0;
                startFailed = false;
                stopIssued = false;
                main.removeCallbacks(closePrecon);
            }
        });
        preconCard = card;
        awaitingStartAt = awaiting;
        stopIssued = stopping;
        preconAutoClose = autoClose;
        renderPreconCard();
    }

    private final Runnable closePrecon = new Runnable() {
        @Override public void run() { if (preconCard != null) sheet.dismiss(); }
    };

    private void renderPreconCard() {
        final PreconditioningSnapshot s = preconditioning;
        StatusCard card = preconCard;
        if (card == null) return;
        StatusCard.Action close = new StatusCard.Action("Close", Ui.SURFACE, dismiss());
        StatusCard.Action cancel = new StatusCard.Action("Cancel request", Ui.RED, stopAction());
        String elapsed = s.requested || s.state == PreconditioningSnapshot.State.ENDED || s.state == PreconditioningSnapshot.State.TIMED_OUT
            ? "Elapsed " + s.elapsed() + "  ·  " + s.indicatorLabel() : s.indicatorLabel();
        boolean autoClose = false;

        // The service answers asynchronously, so the first snapshots after a tap can be stale.
        if (awaitingStartAt > 0) {
            if (s.requested || s.state == PreconditioningSnapshot.State.ERROR
                || s.state == PreconditioningSnapshot.State.WARM || s.state == PreconditioningSnapshot.State.TIMED_OUT) awaitingStartAt = 0;
            else if (SystemClock.uptimeMillis() - awaitingStartAt > 5000) { awaitingStartAt = 0; startFailed = true; }
        }
        if (s.requested) startFailed = false;
        boolean stopping = stopIssued && s.requested && (s.state == PreconditioningSnapshot.State.WAITING
            || s.state == PreconditioningSnapshot.State.ACTIVE || s.state == PreconditioningSnapshot.State.UNKNOWN
            || s.state == PreconditioningSnapshot.State.STOPPING);

        List<View> buttons;
        if (awaitingStartAt > 0) {
            card.set(null, Ui.ACCENT, true, "Sending request…", "Asking the car to start battery conditioning.", null);
            buttons = card.setActions(close);
        } else if (startFailed) {
            card.set(Glyph.Kind.ALERT, Ui.AMBER, false, "Not started", s.detail, s.indicatorLabel());
            buttons = card.setActions(close);
        } else if (stopping || s.state == PreconditioningSnapshot.State.STOPPING) {
            card.set(null, Ui.MUTED, true, "Turning off",
                s.state == PreconditioningSnapshot.State.STOPPING ? s.detail : "Releasing the manual request.", elapsed);
            buttons = card.setActions(close);
        } else switch (s.state) {
            case CHECKING:
                card.set(null, Ui.MUTED, true, "Checking vehicle", s.detail, null);
                buttons = card.setActions(close);
                break;
            case WAITING:
                card.set(Glyph.Kind.BATTERY_HEAT, Ui.AMBER, true, "Waiting for BMS",
                    PreconPresenter.withoutPrefix(s.detail, "Waiting for BMS — "), elapsed);
                buttons = card.setActions(close, cancel);
                break;
            case ACTIVE:
                card.set(Glyph.Kind.CHECK, Ui.GREEN, false, "Preconditioning active", s.detail, elapsed);
                buttons = card.setActions(close, new StatusCard.Action("Turn off", Ui.RED, stopAction()));
                autoClose = preconAutoClose;
                break;
            case WARM:
                card.set(Glyph.Kind.ALERT, Ui.AMBER, false, "Battery warm", s.detail, s.indicatorLabel());
                buttons = card.setActions(close, new StatusCard.Action("Start anyway", Ui.ACCENT, new Runnable() {
                    @Override public void run() {
                        main.removeCallbacks(closePrecon);
                        send(PreconditioningService.START_ANYWAY);
                        awaitingStartAt = SystemClock.uptimeMillis(); startFailed = false; stopIssued = false;
                        preconAutoClose = true; renderPreconCard();
                    }
                }));
                break;
            case TIMED_OUT:
                card.set(Glyph.Kind.ALERT, Ui.AMBER, false, "No activation", s.detail, s.indicatorLabel());
                buttons = s.canStart ? card.setActions(close, startAction()) : card.setActions(close);
                break;
            case UNAVAILABLE:
                card.set(Glyph.Kind.ALERT, Ui.AMBER, false, "Unavailable", s.detail, s.indicatorLabel());
                buttons = card.setActions(close);
                break;
            case UNKNOWN:
                card.set(Glyph.Kind.ALERT, Ui.AMBER, false, "Status unknown", s.detail, elapsed);
                buttons = s.canStop ? card.setActions(close, cancel) : card.setActions(close);
                break;
            case ERROR:
                card.set(Glyph.Kind.ALERT, Ui.RED, false, s.title(), s.detail, elapsed);
                buttons = s.canStop ? card.setActions(close, new StatusCard.Action("Stop request", Ui.RED, stopAction()))
                    : s.canStart ? card.setActions(close, startAction()) : card.setActions(close);
                break;
            default: // IDLE, ENDED
                if (s.indicator == 1) {
                    card.set(Glyph.Kind.BATTERY_HEAT, Ui.GREEN, false, "Conditioning by the car",
                        "The car is conditioning the battery on its own, for example ahead of a navigation charging stop. "
                            + "Tilepad didn't start it, so it doesn't stop it.", s.indicatorLabel());
                    buttons = card.setActions(close);
                } else if (stopIssued || s.state == PreconditioningSnapshot.State.ENDED) {
                    card.set(Glyph.Kind.CHECK, Ui.MUTED, false, "Preconditioning off", s.detail, elapsed);
                    buttons = s.canStart ? card.setActions(close, startAction()) : card.setActions(close);
                    autoClose = preconAutoClose && stopIssued;
                } else if (s.canStart) {
                    card.set(Glyph.Kind.BATTERY_HEAT, Ui.ACCENT, false, "Ready", s.detail, s.indicatorLabel());
                    buttons = card.setActions(close, startAction());
                } else {
                    card.set(Glyph.Kind.ALERT, Ui.AMBER, false, "Can't start right now", s.detail, s.indicatorLabel());
                    buttons = card.setActions(close);
                }
        }
        if (!buttons.equals(sheet.targets())) sheet.setTargets(buttons);
        if (autoClose) {
            preconAutoClose = false; // only once per popup
            main.postDelayed(closePrecon, 3000);
        }
    }

    private Runnable stopAction() {
        return new Runnable() { @Override public void run() {
            main.removeCallbacks(closePrecon);
            send(PreconditioningService.STOP);
            stopIssued = true;
            preconAutoClose = true;
            renderPreconCard();
        } };
    }

    private StatusCard.Action startAction() {
        return new StatusCard.Action("Start", Ui.ACCENT, new Runnable() { @Override public void run() {
            main.removeCallbacks(closePrecon);
            send(PreconditioningService.START);
            awaitingStartAt = SystemClock.uptimeMillis();
            startFailed = false;
            stopIssued = false;
            preconAutoClose = true;
            renderPreconCard();
        } });
    }

    // ---- Wi-Fi ----

    private void refreshConnection() {
        if (connectionReading) return;
        if (preview) { onConnection(PreviewData.connection()); return; }
        connectionReading = true;
        final int epoch = connectionEpoch;
        new Thread(new Runnable() {
            @Override public void run() {
                ConnectionSnapshot value;
                try {
                    WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (manager == null) value = ConnectionSnapshot.unknown();
                    else {
                        WifiInfo info = manager.getConnectionInfo();
                        SupplicantState supplicant = info == null ? null : info.getSupplicantState();
                        value = ConnectionSnapshot.read(manager.isWifiEnabled(),
                            supplicant == SupplicantState.COMPLETED,
                            info == null ? null : info.getSSID(), info == null ? 0 : info.getIpAddress());
                        if (info != null) value = value.withLink(WifiManager.calculateSignalLevel(info.getRssi(), 4), info.getLinkSpeed());
                        value = value.onTheWay(manager.getWifiState() == WifiManager.WIFI_STATE_ENABLING,
                            joining(supplicant), false, info == null ? null : info.getSSID());
                    }
                } catch (SecurityException e) { value = ConnectionSnapshot.unknown(); }
                final ConnectionSnapshot result = value;
                main.post(new Runnable() { @Override public void run() {
                    connectionReading = false;
                    if (!isFinishing() && resumed && epoch == connectionEpoch) onConnection(result);
                } });
            }
        }, "tilepad-wifi").start();
    }

    /** Associating with a network, or associated and waiting for DHCP. */
    private static boolean joining(SupplicantState s) {
        if (s == null) return false;
        switch (s) {
            case ASSOCIATING: case ASSOCIATED: case AUTHENTICATING:
            case FOUR_WAY_HANDSHAKE: case GROUP_HANDSHAKE: case COMPLETED: return true;
            default: return false;
        }
    }

    // Hotspot-only units: the radio can't join a network until android.hardware.wifi exists.
    // Android reads features at boot, so the file existing without the feature means "reboot".

    private static boolean wifiClientPending() { return new File(WIFI_CLIENT_XML).exists(); }

    private void bindWifiClientOff() {
        if (wifiTile != null) wifiTile.bindClientOff(wifiClientPending());
    }

    private void offerWifiClient() {
        final StatusCard card = new StatusCard(ui);
        if (wifiClientPending()) {
            sheet.show(card, 680, null);
            askRebootForWifiClient(card);
            return;
        }
        card.set(Glyph.Kind.WIFI_OFF, Ui.ACCENT, false, "Turn on Wi-Fi?",
            "The car's Wi-Fi is set up only for wireless CarPlay and Android Auto. Turn it on to join "
                + "Wi-Fi networks too. This needs a reboot.",
            "While the car is on a Wi-Fi network, wireless CarPlay and Android Auto may not connect.");
        List<View> buttons = card.setActions(
            new StatusCard.Action("Cancel", Ui.SURFACE, dismiss()),
            new StatusCard.Action("Turn on", Ui.ACCENT, new Runnable() { @Override public void run() { enableWifiClient(card); } }));
        sheet.show(card, 680, null);
        sheet.setTargets(buttons);
    }

    private void enableWifiClient(final StatusCard card) {
        card.set(null, Ui.ACCENT, true, "Turning on Wi-Fi…", "This takes a few seconds.", null);
        sheet.setTargets(card.setActions());
        new Thread(new Runnable() {
            @Override public void run() {
                final RootShell.Result result = RootShell.run("sh " + WIFI_CLIENT_SCRIPT + " enable");
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    bindWifiClientOff();
                    if (result.code == 0 && wifiClientPending()) {
                        askRebootForWifiClient(card);
                        return;
                    }
                    String detail = result.output.isEmpty() ? "nemesis exited with code " + result.code : result.output;
                    card.set(Glyph.Kind.ALERT, Ui.RED, false, "Couldn't turn on Wi-Fi", detail, null);
                    sheet.setTargets(card.setActions(new StatusCard.Action("Close", Ui.SURFACE, dismiss())));
                } });
            }
        }, "tilepad-wifi-client").start();
    }

    /** Shows the reboot prompt on a card that is already on screen. */
    private void askRebootForWifiClient(final StatusCard card) {
        card.set(Glyph.Kind.CHECK, Ui.GREEN, false, "Reboot to finish",
            "Wi-Fi turns on after the head unit reboots. Then tap the Wi-Fi tile to join a network.", null);
        List<View> buttons = card.setActions(
            new StatusCard.Action("Later", Ui.SURFACE, dismiss()),
            new StatusCard.Action("Reboot", Ui.RED, new Runnable() { @Override public void run() { reboot(card); } }));
        sheet.setTargets(buttons);
    }

    private void onConnection(ConnectionSnapshot value) {
        connection = value;
        // Turning the hotspot off, the radio on and joining a saved network takes several
        // seconds after the ADB tile settles; say so rather than "Off" or "No network".
        if (wifiExpectedSince > 0 && (value.state == ConnectionSnapshot.State.CONNECTED
                || SystemClock.uptimeMillis() - wifiExpectedSince > WIFI_EXPECTED_MS)) wifiExpectedSince = 0;
        if (wifiTile != null) wifiTile.bind(value.onTheWay(false, false, wifiExpectedSince > 0, null));
    }

    // ---- Tweaks summary ----

    private void refreshTweaks() {
        if (tweaksTile == null || tweaksReading) return;
        if (preview) { tweaks = PreviewData.tweaks(); tweaksTile.bind(tweaks); return; }
        if (!RootShell.available()) {
            tweaks = TweaksSnapshot.unavailable("NEMESIS is not installed"); tweaksTile.bind(tweaks); return;
        }
        tweaksReading = true;
        final int epoch = tweaksEpoch;
        new Thread(new Runnable() {
            @Override public void run() {
                RootShell.Result result = RootShell.run("sh /data/nemesis/apps/tilepad/bin/tweaks.sh summary");
                final TweaksSnapshot snapshot = result.code == 0 ? TweaksSnapshot.parse(result.output)
                    : TweaksSnapshot.unavailable(result.output);
                main.post(new Runnable() {
                    @Override public void run() {
                        tweaksReading = false;
                        if (!resumed || isFinishing()) return;
                        if (epoch != tweaksEpoch) { refreshTweaks(); return; }
                        tweaks = snapshot;
                        if (tweaksTile != null) tweaksTile.bind(tweaks);
                        applyInsets();
                    }
                });
            }
        }, "tilepad-tweaks-summary").start();
    }

    /**
     * The right inset keeps tiles clear of Hyundai's split-screen handle on the right edge. With
     * the handle hidden by the tweak, the sides match so the grid sits centred.
     */
    private int rightInset() { return ui.dp(prefs.getBoolean("handleHidden", false) ? 24 : 64); }

    private void applyInsets() {
        // The last summary's answer is kept so the grid doesn't shift when the next one arrives.
        if (tweaks.problem == null && tweaks.enabled >= 0 && tweaks.handleHidden != prefs.getBoolean("handleHidden", false))
            prefs.edit().putBoolean("handleHidden", tweaks.handleHidden).apply();
        if (scroll != null && scroll.getPaddingRight() != rightInset())
            scroll.setPadding(ui.dp(24), ui.dp(16), rightInset(), ui.dp(16));
    }

    // ---- ADB ----

    private void refreshAdb() {
        if (adbTile == null) return;
        if (preview) { onAdb(PreviewData.adb()); return; }
        if (!RootShell.available()) { onAdb(AdbSnapshot.of(AdbSnapshot.State.OFF, 0)); return; }
        onAdb(AdbSnapshot.read(prop("init.svc.adbd"), prop("service.adb.tcp.port"), prop("persist.adb.tcp.port"), sockets()));
    }

    /** /proc/net/tcp and tcp6 together, or null if neither can be read. */
    private static String sockets() {
        StringBuilder out = new StringBuilder(); boolean any = false;
        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(path))) {
                for (String line; (line = in.readLine()) != null; ) out.append(line).append('\n');
                any = true;
            } catch (java.io.IOException ignored) { }
        }
        return any ? out.toString() : null;
    }

    /** android.os.SystemProperties.get (hidden API); "" if it can't be read. */
    private static String prop(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    private void onAdb(AdbSnapshot value) {
        // Keep the optimistic Starting/Stopping display until adbd settles (it restarts on the
        // way on), so a read in between doesn't flicker the tile back.
        boolean done = false;
        if (adbPendingSince > 0) {
            boolean settled = adbPendingOn ? value.state == AdbSnapshot.State.ON
                                           : value.state == AdbSnapshot.State.OFF;
            if (value.state == AdbSnapshot.State.ERROR || settled) {
                adbPendingSince = 0;
                done = settled;
            } else if (SystemClock.uptimeMillis() - adbPendingSince < 12000) {
                value = AdbSnapshot.of(adbPendingOn ? AdbSnapshot.State.STARTING : AdbSnapshot.State.STOPPING, value.port);
            } else {
                // Give up waiting: show the command's error if it had one, else what adbd reports.
                adbPendingSince = 0;
                if (adbPendingError != null) value = AdbSnapshot.error(adbPendingError);
            }
            if (adbPendingSince == 0) adbPendingError = null;
        }
        adb = value;
        if (adbTile != null) adbTile.bind(value);
        // A finished toggle closes the popup rather than offering to undo it; errors stay up.
        if (adbCard != null) {
            if (done) sheet.dismiss();
            else renderAdbCard();
        }
    }

    private void onAdbTap() {
        final StatusCard card = new StatusCard(ui);
        sheet.show(card, 720, new Runnable() {
            @Override public void run() { if (adbCard == card) adbCard = null; }
        });
        adbCard = card;
        renderAdbCard();
    }

    private void renderAdbCard() {
        final StatusCard card = adbCard;
        if (card == null) return;
        AdbSnapshot s = adb;
        StatusCard.Action close = new StatusCard.Action("Close", Ui.SURFACE, dismiss());
        List<View> buttons;
        switch (s.state) {
            case ON:
                card.set(Glyph.Kind.BUG, Ui.GREEN, false, "ADB is on",
                    "Any computer on this Wi-Fi network can connect with “adb connect " + adbAddress(s.port)
                        + "” and gets a shell. Wireless CarPlay and Android Auto are off while Wi-Fi is on. "
                        + "Turn it off when you're done.", null);
                buttons = card.setActions(close,
                    new StatusCard.Action("Turn off", Ui.RED, new Runnable() { @Override public void run() { toggleAdb(false); } }));
                break;
            case STARTING:
                card.set(null, Ui.AMBER, true, "Turning on ADB", "Starting ADB over Wi-Fi and turning on Wi-Fi.", null);
                buttons = card.setActions(close);
                break;
            case STOPPING:
                card.set(null, Ui.MUTED, true, "Turning off ADB", "Stopping ADB over Wi-Fi.", null);
                buttons = card.setActions(close);
                break;
            case ERROR:
                card.set(Glyph.Kind.ALERT, Ui.RED, false, "ADB error",
                    s.detail.isEmpty() ? "Tilepad couldn't change ADB. Try again." : s.detail, null);
                buttons = card.setActions(close, adbTurnOn());
                break;
            case CHECKING:
                card.set(null, Ui.MUTED, true, "Checking ADB", "Reading the current state.", null);
                buttons = card.setActions(close);
                break;
            default: // OFF
                if (!RootShell.available()) {
                    card.set(Glyph.Kind.ALERT, Ui.AMBER, false, "ADB unavailable",
                        "Turning ADB on needs NEMESIS, which isn't installed on this head unit.", null);
                    buttons = card.setActions(close);
                } else {
                    card.set(Glyph.Kind.BUG, Ui.AMBER, false, "Turn on ADB?",
                        "This turns on ADB over Wi-Fi, and Wi-Fi itself, now and at every boot until you turn it off. "
                            + "Any computer on the same network can connect without approving a key and gets a "
                            + "root-capable shell. Wi-Fi shuts off the hotspot wireless CarPlay and Android Auto use.",
                        wifiClientPending() ? null
                            : "This head unit's Wi-Fi only runs the CarPlay hotspot: turn on Wi-Fi from the Wi-Fi tile first.");
                    buttons = card.setActions(close, adbTurnOn());
                }
        }
        if (!buttons.equals(sheet.targets())) sheet.setTargets(buttons);
    }

    private StatusCard.Action adbTurnOn() {
        return new StatusCard.Action("Turn on", Ui.ACCENT, new Runnable() { @Override public void run() { toggleAdb(true); } });
    }

    private String adbAddress(int port) {
        String host = connection.state == ConnectionSnapshot.State.CONNECTED ? connection.ip : "<ip>";
        return host + ":" + (port > 0 ? port : 5555);
    }

    private void toggleAdb(final boolean on) {
        adbPendingSince = SystemClock.uptimeMillis();
        adbPendingOn = on;
        adbPendingError = null;
        adb = AdbSnapshot.of(on ? AdbSnapshot.State.STARTING : AdbSnapshot.State.STOPPING, adb.port);
        if (adbTile != null) adbTile.bind(adb);
        if (on && support.wifiClient && connection.state != ConnectionSnapshot.State.CONNECTED) {
            wifiExpectedSince = SystemClock.uptimeMillis();
            onConnection(connection);
        }
        if (adbCard != null) renderAdbCard();
        final int epoch = adbEpoch;
        new Thread(new Runnable() {
            @Override public void run() {
                RootShell.Result r = RootShell.run("sh " + ADB_TILE_SCRIPT + " " + (on ? "on" : "off"));
                // The root command can report a failure even though the change went through, so
                // its error only shows if adbd doesn't reach the new state (see onAdb).
                final String failed = r.code == 0 ? null : r.output.isEmpty() ? "nemesis exited with code " + r.code : r.output;
                if (failed != null) Log.w(TAG, "adb-tile " + (on ? "on" : "off") + " exited " + r.code + ": " + r.output);
                final String wifi = failed == null && on ? wifiOn() : null;
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || !resumed || epoch != adbEpoch) return;
                    if (on && (failed != null || wifi != null)) {
                        // Wi-Fi wasn't turned on after all.
                        wifiExpectedSince = 0;
                        onConnection(connection);
                    }
                    if (wifi != null) {
                        adbPendingSince = 0;
                        onAdb(AdbSnapshot.error(wifi));
                    } else {
                        adbPendingError = failed;
                        refreshAdb();
                    }
                } });
            }
        }, "tilepad-adb-toggle").start();
    }

    /** Turns the Wi-Fi client on as the system user (see bin/adb-tile.sh wifi); null once it's on. */
    private static String wifiOn() {
        RootShell.Result r = RootShell.run("sh " + ADB_TILE_SCRIPT + " wifi");
        if (r.code == 0) return null;
        return r.output.isEmpty() ? "Couldn't turn on Wi-Fi (exit code " + r.code + ")." : r.output;
    }

    // ---- TUNE knob ----

    @Override public void onSheetChanged() {
        sheetKnob = -1;
        for (View v : sheet.targets()) v.setActivated(false);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KEY_TUNE_UP || code == KEY_TUNE_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (down) knob(code == KEY_TUNE_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        if (code == KEY_TUNE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            // Short press only: a TUNE hold is left to the stock long-press handling.
            if (down) {
                if (event.getRepeatCount() == 0) { enterDown = true; enterLong = false; enterAt = SystemClock.uptimeMillis(); }
                else enterLong = true;
            } else {
                if (enterDown && !enterLong && SystemClock.uptimeMillis() - enterAt < 800) knobEnter();
                enterDown = false;
                enterLong = false;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void knob(int delta) {
        if (sheet.isShowing()) {
            List<View> targets = sheet.targets();
            boolean[] enabled = new boolean[targets.size()];
            Arrays.fill(enabled, true);
            sheetKnob = KnobFocus.next(sheetKnob, delta, enabled);
            for (int i = 0; i < targets.size(); i++) targets.get(i).setActivated(i == sheetKnob);
        } else if (addPage != null) {
            addPage.knob(delta);
        } else if (!grid.isEditing()) {
            boolean[] enabled = new boolean[grid.tiles().size()];
            Arrays.fill(enabled, true);
            grid.setKnobIndex(KnobFocus.next(grid.knobIndex(), delta, enabled));
        }
    }

    private void knobEnter() {
        if (sheet.isShowing()) {
            List<View> targets = sheet.targets();
            if (sheetKnob >= 0 && sheetKnob < targets.size()) targets.get(sheetKnob).performClick();
            else knob(1);
        } else if (addPage != null) {
            addPage.knobEnter();
        } else if (grid.isEditing()) {
            grid.setEditing(false);
        } else {
            int index = grid.knobIndex();
            if (index >= 0 && index < grid.tiles().size()) onTileTap(grid.tiles().get(index));
            else knob(1);
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private static int keyCode(String name, int fallback) {
        int code = KeyEvent.keyCodeFromString(name);
        return code > 0 ? code : fallback;
    }
}
