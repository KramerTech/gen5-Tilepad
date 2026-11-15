"""Run actual TweaksRuntime heater and cooling boot/passenger paths against deterministic fixtures."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
work = ROOT / "build/heater-runtime-contract"
if work.exists():
    shutil.rmtree(work)
work.mkdir(parents=True)

stubs = {
    "android/os/SystemClock": "public class SystemClock { public static long now = 100000; public static long elapsedRealtime() { return now; } }",
    "android/os/Looper": "public class Looper { public static void prepareMainLooper() {} public static Looper myLooper() { return new Looper(); } public static void loop() {} }",
    "android/os/Process": "public class Process { public static int myPid() { return 1234; } }",
    "android/os/IBinder": "public interface IBinder {}",
    "android/os/Handler": """import java.util.*; public class Handler {
 private static final List<Object[]> q = new ArrayList<>();
 public Handler() {} public Handler(Looper l) {}
 public boolean post(Runnable r) { return postDelayed(r, 0); }
 public boolean postDelayed(Runnable r, long d) { q.add(new Object[]{r, SystemClock.now + d}); return true; }
 public void removeCallbacks(Runnable r) { q.removeIf(x -> x[0] == r); }
 public void drain() { for (boolean again = true; again;) { again = false; for (Object[] x : new ArrayList<>(q)) if ((Long) x[1] <= SystemClock.now && q.remove(x)) { ((Runnable) x[0]).run(); again = true; } } }
 public void advance(long ms) { SystemClock.now += ms; drain(); }
 public void reset() { q.clear(); }
}""",
    "android/os/FileObserver": """public abstract class FileObserver {
 public static final int MOVED_TO = 1, CLOSE_WRITE = 2;
 public FileObserver(String p, int m) {}
 public abstract void onEvent(int event, String path);
 public void startWatching() {}
}""",
    "android/content/Context": "public class Context { public android.content.ContentResolver getContentResolver() { return new android.content.ContentResolver(); } }",
    "android/content/ContentResolver": "public class ContentResolver { public void registerContentObserver(Object u, boolean d, android.database.ContentObserver o) {} }",
    "android/content/IntentFilter": "public class IntentFilter { public IntentFilter() {} public IntentFilter(String a) {} public void addAction(String a) {} }",
    "android/content/Intent": "public class Intent { public static final String ACTION_TIME_CHANGED = \"t\", ACTION_TIMEZONE_CHANGED = \"tz\", ACTION_DATE_CHANGED = \"d\", ACTION_SCREEN_ON = \"s\"; }",
    "android/content/BroadcastReceiver": "public abstract class BroadcastReceiver { public abstract void onReceive(Context c, Intent i); }",
    "android/database/ContentObserver": "public abstract class ContentObserver { public ContentObserver(android.os.Handler h) {} public void onChange(boolean s) {} }",
    "android/provider/Settings": "public class Settings { public static class System { public static Object getUriFor(String n) { return n; } } }",
    "android/app/Instrumentation": "public class Instrumentation {}",
    "com/tilepad/tweakcore/ClimateController": """import android.os.Handler; public class ClimateController {
 public static boolean buckled;
 public ClimateController(Handler h) {}
 public void configure(boolean sync, boolean driverOnly) {}
 public static boolean passengerBuckled() { return buckled; }
}""",
    "com/tilepad/tweakcore/ProjectionController": "import android.content.*; import android.os.*; public class ProjectionController { public ProjectionController(Context c, Handler h) {} public void configure(boolean on) {} }",
    "com/tilepad/tweakcore/ChargeAwayController": "import android.content.*; public class ChargeAwayController { public ChargeAwayController(Context c) {} public void configure(Config c) {} }",
    "com/tilepad/tweakcore/DstController": "public class DstController { public static long update(Config c, long now) { return Long.MAX_VALUE; } }",
    "com/tilepad/tweakcore/TweaksCommand": "public class TweaksCommand { public static Config config = new Config(new java.util.Properties()); public static Config load() { return config; } public static void recover() {} }",
    "com/tilepad/tweakcore/Platform": """import java.io.*; import java.util.*;
public class Platform {
 public static final File STATE = new File(System.getProperty("fixture.state"));
 public static String boot = "boot-1";
 public static double tempC = 30.0;
 public static int drvOpt = 6, astOpt = 6, wheelOpt = 1;
 public static boolean ignition = true;
 public static final List<int[]> commands = new ArrayList<>();
 public static final Map<String, String> statuses = new LinkedHashMap<>();
 public static Firmware.Release vehicle() { return Firmware.SUPPORTED[0]; }
 public static void requireVehicle() {}
 public static void requireDualZone() {}
 public static void initialize() { STATE.mkdirs(); }
 public static String boot() { return boot; }
 public static boolean ignitionOn() { return ignition; }
 public static String property(String n) { return ""; }
 public static String exec(String... args) {
  if ("dumpsys".equals(args[0]) && "com.hkmc.micom.datc".equals(args[1])) {
   return String.format(Locale.US, "  AmbientTempC : 0x52\\n  mAmbientTempC: %.2f\\n", tempC);
  }
  throw new AssertionError(Arrays.toString(args));
 }
 public static int integer(String svc, String desc, int tx, int... args) {
  Firmware.Usm usm = vehicle().usm;
  if (tx == usm.driverFitted) return drvOpt;
  if (tx == usm.passengerFitted) return astOpt;
  if (tx == usm.wheelFitted) return wheelOpt;
  throw new AssertionError("Unexpected integer tx: " + tx);
 }
 public static void command(String svc, String desc, int tx, int... vals) {
  commands.add(new int[]{tx, vals[0]});
 }
 public static void status(String id, String msg) { statuses.put(id, msg); }
}""",
}

for name, body in stubs.items():
    p = work / "src" / f"{name}.java"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text("package " + name.rsplit("/", 1)[0].replace("/", ".") + ";\n" + body)

test = work / "src/com/tilepad/tweakcore/HeaterRuntimeTest.java"
test.write_text("""package com.tilepad.tweakcore;
import java.io.*; import java.lang.reflect.*; import java.util.*; import android.os.*;
public class HeaterRuntimeTest {
 static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
 static void setField(Object target, String name, Object val) throws Exception {
  Field f = TweaksRuntime.class.getDeclaredField(name); f.setAccessible(true); f.set(target, val);
 }
 static void callBegin(TweaksRuntime rt) throws Exception {
  Method m = TweaksRuntime.class.getDeclaredMethod("beginHeaters"); m.setAccessible(true); m.invoke(rt);
 }
 static TweaksRuntime newRuntime(Handler h) throws Exception {
  Constructor<TweaksRuntime> c = TweaksRuntime.class.getDeclaredConstructor(); c.setAccessible(true);
  TweaksRuntime rt = c.newInstance(); setField(rt, "handler", h); return rt;
 }
 static Config heaterConfig(boolean cooling) {
  Config c = new Config(new Properties());
  c.set("heaters", "true"); c.set("cooling", Boolean.toString(cooling));
  c.set("driver", "true"); c.set("passenger", "true"); c.set("wheel", "true");
  TweaksCommand.config = c; return c;
 }
 public static void main(String[] args) throws Exception {
  Platform.STATE.mkdirs();
  Handler h = new Handler();
  // 1. Warm boot with cooling off leaves existing skip behavior and touches no wheel or seat.
  heaterConfig(false); Platform.boot = "b1"; Platform.tempC = 33.0; Platform.commands.clear();
  TweaksRuntime rt = newRuntime(h); callBegin(rt); h.drain(); h.advance(3000);
  check(Platform.commands.isEmpty(), "cooling disabled skips warm boot");

  // 2. Warm boot (30.0 C -> coolMed 2 -> enum 4) cools driver when option == 6, skips unventilated wheel and unventilated passenger (option == 2).
  h.reset(); heaterConfig(true); Platform.boot = "b2"; Platform.tempC = 30.0; Platform.drvOpt = 6; Platform.astOpt = 2; Platform.commands.clear();
  rt = newRuntime(h); callBegin(rt); h.drain(); h.advance(3000);
  check(Platform.commands.size() == 1, "only ventilated driver cooled, got " + Platform.commands.size());
  check(Platform.commands.get(0)[0] == Firmware.SUPPORTED[0].usm.driverHeat && Platform.commands.get(0)[1] == 4, "driver vent Med = 4");
  check(Platform.statuses.get("heaters").contains("isn't ventilated"), "unventilated passenger reported");

  // 3. Both seats ventilated (option == 6), hot (32.5 C -> High -> enum 5): passenger cools when belt buckles after a simulated service restart.
  h.reset(); heaterConfig(true); Platform.boot = "b3"; Platform.tempC = 32.5; Platform.drvOpt = 6; Platform.astOpt = 6; ClimateController.buckled = false; Platform.commands.clear();
  rt = newRuntime(h); callBegin(rt); h.drain(); h.advance(3000);
  check(Platform.commands.size() == 1 && Platform.commands.get(0)[1] == 5, "driver vent High = 5");
  check(new File(Platform.STATE, "heaters-passenger").isFile(), "passenger cooling wait persisted");
  // Simulate service restart in the same boot: resumePassenger restores cooling level 3 -> enum 5.
  h.reset(); TweaksRuntime restarted = newRuntime(h); callBegin(restarted); h.drain();
  ClimateController.buckled = true; h.advance(1000);
  check(Platform.commands.size() == 2 && Platform.commands.get(1)[0] == Firmware.SUPPORTED[0].usm.passengerHeat && Platform.commands.get(1)[1] == 5, "passenger vent High = 5 after restart + buckle");
  check(!new File(Platform.STATE, "heaters-passenger").isFile(), "wait file cleared after cooling");

  // 4. Cold start still heats driver, steering wheel (16), and passenger even when cooling is enabled.
  h.reset(); heaterConfig(true); Platform.boot = "b4"; Platform.tempC = 5.0; Platform.drvOpt = 2; Platform.astOpt = 2; ClimateController.buckled = true; Platform.commands.clear();
  rt = newRuntime(h); callBegin(rt); h.drain(); h.advance(3000);
  check(Platform.commands.size() == 3, "cold start heats driver, wheel, passenger");
  check(Platform.commands.get(0)[1] == 8 && Platform.commands.get(1)[0] == 16 && Platform.commands.get(2)[1] == 8, "cold start uses heat enums 8 + wheel");

  // 5. Same head-unit boot, car turns off and restarts warm (28.0 C -> coolLow 1 -> enum 3): cooling runs on the new car start.
  Platform.drvOpt = 6; Platform.astOpt = 6; Platform.tempC = 28.0; Platform.commands.clear();
  Platform.ignition = false; h.advance(2000);
  Platform.ignition = true; h.advance(2000); h.advance(3000);
  check(Platform.commands.size() == 2 && Platform.commands.get(0)[1] == 3 && Platform.commands.get(1)[1] == 3, "same-boot restart cools both seats Low = 3");
  System.out.println("5 heater/cooling runtime scenarios passed (mapped fixtures; no real vehicle writes)");
 }
}
""")

production = ROOT / "tweaks-core/src/main/java/com/tilepad/tweakcore"
names = ["Config", "Files", "Firmware", "HeaterPolicy", "TweaksRuntime", "VehicleLocation"]
classes = work / "classes"
classes.mkdir()
subprocess.run(
    ["javac", "--release", "8", "-d", str(classes), *map(str, (work / "src").rglob("*.java")), *[str(production / f"{n}.java") for n in names]],
    check=True,
)
r = subprocess.run(
    ["java", f"-Dfixture.state={work / 'state'}", "-cp", str(classes), "com.tilepad.tweakcore.HeaterRuntimeTest"],
    check=True,
    capture_output=True,
    text=True,
    timeout=30,
)
print(r.stdout.strip())
