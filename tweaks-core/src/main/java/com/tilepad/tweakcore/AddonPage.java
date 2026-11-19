package com.tilepad.tweakcore;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * A settings page that an installed NEMESIS package describes in its own tilepad.properties. It's
 * data only: Tilepad draws it with its own controls, reads what it shows from system properties,
 * and changes a setting or sends a request only through the package's bin/ctl.sh (AddonCtl).
 * docs/TWEAKS.md describes the format for package authors.
 *
 * The file, schema 1 (numbered entries are taken in number order; a bad entry is skipped and noted):
 *   schema=1, title=…, help=… (\n for a new line, **bold**), results=… (the property its ctl.sh
 *   answers requests in, for actions)
 *   palette.P=RRGGBB,… (up to 64), palette.P.names=Name,… (optional, same order)
 *   status.N.text=… with {prop} or {prop|value=word,…,*=word}; status.N.when=…; status.N.problem=1
 *   control.N.type=, with:
 *     toggle       prop, label, default, on (1), off (0)
 *     stepper      prop, label, min, max, step (1), default, unit (after the number), zero (the
 *                  reading at min), of (the reading as "N / of")
 *     temperature  prop, label, min, max, default (°C; stored with one decimal), unitprop (a
 *                  property that's C or F; F when unset): shown and stepped by degrees of that unit
 *     choices      prop, default, option.M=value|label|note, layout=vertical|horizontal, label (a note above)
 *     color        prop, label, default (RRGGBB), palette (a template naming one of the palettes)
 *     action       label, request (a ctl.sh req verb), confirm.title, confirm.text, confirm.button,
 *                  result.WORD=text, timeout (seconds, 15; at most 120)
 *     heading      label;  note  text (a template), problem=1
 *     each may have when=…; a setting's name in ctl.sh is prop after persist.<word>., or key=…
 * A condition is clauses joined by " and ": prop = v, prop != v, prop in a,b,c, prop set, prop unset.
 * No Android, so it can be tested on the host.
 */
public final class AddonPage {
    public static final String FILE = "tilepad.properties";
    public static final int SCHEMA = 1;
    /** The most a page file may hold, in bytes; the root shell's reply holds a little more. */
    public static final int MAX_FILE = 24000;
    static final int MAX_CONTROLS = 96, MAX_STATUS = 16, MAX_OPTIONS = 16, MAX_TEXT = 4000, MAX_LABEL = 120;
    static final int MAX_PALETTE = 64;
    /** Android 4.4's longest system property name (PROP_NAME_MAX - 1). */
    static final int MAX_PROP = 31;

    /** One line under the page's switch that shows while its condition holds. */
    public static final class Status {
        public final String text;
        public final Condition when;
        public final boolean problem;
        Status(String text, Condition when, boolean problem) { this.text = text; this.when = when; this.problem = problem; }
    }

    public enum Type { TOGGLE, STEPPER, TEMPERATURE, CHOICES, COLOR, ACTION, HEADING, NOTE }

    /** One choice of a choices control. */
    public static final class Option {
        public final String value, label, note;
        Option(String value, String label, String note) { this.value = value; this.label = label; this.note = note; }
    }

    /** A named list of colors, each six hex digits, with names when the page gives them. */
    public static final class Palette {
        public final List<String> colors, names;
        Palette(List<String> colors, List<String> names) { this.colors = colors; this.names = names; }
        /** Whether color is one of its colors. */
        public boolean has(String color) { return colors.contains(color); }
        /** The name shown for color: its name in this palette, or "#RRGGBB". */
        public String nameOf(String color) {
            int i = colors.indexOf(color);
            return i >= 0 && names != null ? names.get(i) : "#" + color;
        }
    }

    /** One control, in page order. Fields a type doesn't use are null, 0 or empty. */
    public static final class Control {
        public Type type;
        /** The system property it shows (and sets, through ctl.sh as key). */
        public String prop, key;
        public String label, text, def, on, off, unit, zero, unitprop, palette, request;
        public String confirmTitle, confirmText, confirmButton;
        public int min, max, step = 1, of, timeoutS = 15;
        public List<Option> options = Collections.emptyList();
        public Map<String, String> results = Collections.emptyMap();
        /** A choices control's layout as the page gives it: side by side, one per line, or neither (Tilepad decides). */
        public boolean horizontal, vertical, problem;
        public Condition when = Condition.ALWAYS;

        Control() { }

        /** Whether this control sets a property. */
        public boolean sets() {
            return type == Type.TOGGLE || type == Type.STEPPER || type == Type.TEMPERATURE || type == Type.CHOICES || type == Type.COLOR;
        }
    }

    /** The package's folder under /data/nemesis/apps. */
    public final String id;
    public final String title, help;
    /** The property its ctl.sh writes its requests' results to; null without actions. */
    public final String results;
    public final Map<String, Palette> palettes;
    public final List<Status> status;
    public final List<Control> controls;
    /** What was skipped and why, one line each; empty when the whole file was used. */
    public final List<String> problems;

    private AddonPage(String id, String title, String help, String results, Map<String, Palette> palettes, List<Status> status,
                      List<Control> controls, List<String> problems) {
        this.id = id; this.title = title; this.help = help; this.results = results;
        this.palettes = Collections.unmodifiableMap(palettes);
        this.status = Collections.unmodifiableList(status);
        this.controls = Collections.unmodifiableList(controls);
        this.problems = Collections.unmodifiableList(problems);
    }

    /** A page that can't be used at all: an unusable folder name, size, schema or title. */
    public static final class Invalid extends Exception {
        Invalid(String reason) { super(reason); }
    }

    /** Package folder names Tilepad takes: lower case letters, digits, '.', '_' and '-'. */
    public static boolean folderName(String id) {
        return id != null && id.length() <= 64 && id.matches("[a-z0-9][a-z0-9._-]*") && !id.contains("..");
    }

    /** Reads package id's page file text. */
    public static AddonPage parse(String id, String text) throws Invalid {
        if (!folderName(id)) throw new Invalid("its folder name isn't one Tilepad takes");
        if (text == null || text.length() > MAX_FILE) throw new Invalid("its page file is missing or too large");
        Properties p = new Properties();
        try { p.load(new StringReader(text)); } catch (IOException | IllegalArgumentException e) { throw new Invalid("its page file can't be read"); }
        if (!String.valueOf(SCHEMA).equals(p.getProperty("schema", "").trim())) throw new Invalid("its page file is for another version of Tilepad");
        String title = text(p.getProperty("title"), MAX_LABEL);
        if (title == null) throw new Invalid("its page file has no title");
        String help = text(p.getProperty("help", ""), MAX_TEXT);
        List<String> problems = new ArrayList<>();
        String results = p.getProperty("results", "").trim();
        if (!results.isEmpty() && (!propName(results) || results.startsWith("persist."))) {
            problems.add("results: not a property Tilepad reads answers from");
            results = "";
        }

        Map<String, Palette> palettes = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("palette.") || key.endsWith(".names")) continue;
            String pname = key.substring("palette.".length());
            List<String> colors = new ArrayList<>();
            for (String c : p.getProperty(key).split(",")) {
                String hex = c.trim().toUpperCase(Locale.US);
                if (hex.matches("[0-9A-F]{6}") && !colors.contains(hex)) colors.add(hex);
            }
            if (!pname.matches("[a-z0-9_]{1,16}") || colors.isEmpty() || colors.size() > MAX_PALETTE) {
                problems.add("palette " + pname + ": its name or colors aren't ones Tilepad takes");
                continue;
            }
            List<String> names = null;
            String n = p.getProperty(key + ".names");
            if (n != null) {
                names = new ArrayList<>();
                for (String s : n.split(",", -1)) names.add(text(s, 40) == null ? "" : text(s, 40));
                if (names.size() != colors.size()) { problems.add("palette " + pname + ": its names don't match its colors"); names = null; }
            }
            palettes.put(pname, new Palette(Collections.unmodifiableList(colors), names == null ? null : Collections.unmodifiableList(names)));
        }

        List<Status> status = new ArrayList<>();
        for (int n : numbers(p, "status.", MAX_STATUS, problems)) {
            String at = "status." + n + ".";
            String t = text(p.getProperty(at + "text"), MAX_LABEL * 2);
            Condition when = Condition.parse(p.getProperty(at + "when"));
            if (t == null || when == null) { problems.add("status " + n + ": no text, or a condition Tilepad can't read"); continue; }
            status.add(new Status(t, when, "1".equals(p.getProperty(at + "problem"))));
        }

        List<Control> controls = new ArrayList<>();
        Set<String> props = new LinkedHashSet<>();
        for (int n : numbers(p, "control.", MAX_CONTROLS, problems)) {
            String why = control(p, n, controls, props, palettes, !results.isEmpty());
            if (why != null) problems.add("control " + n + ": " + why);
        }

        return new AddonPage(id, title, help == null ? "" : help, results.isEmpty() ? null : results, palettes, status, controls, problems);
    }

    /** Adds control n to controls (its property to props), or says why it was skipped. */
    private static String control(Properties p, int n, List<Control> controls, Set<String> props, Map<String, Palette> palettes, boolean named) {
        String at = "control." + n + ".";
        Control c = new Control();
        String typeName = p.getProperty(at + "type", "").trim();
        try { c.type = Type.valueOf(typeName.toUpperCase(Locale.US)); } catch (IllegalArgumentException e) { return "type \"" + typeName + "\" isn't one Tilepad has"; }
        c.when = Condition.parse(p.getProperty(at + "when"));
        if (c.when == null) return "a condition Tilepad can't read";
        c.label = text(p.getProperty(at + "label"), MAX_LABEL);
        c.problem = "1".equals(p.getProperty(at + "problem"));
        switch (c.type) {
            case HEADING:
                if (c.label == null) return "no label";
                controls.add(c);
                return null;
            case NOTE:
                c.text = text(p.getProperty(at + "text"), MAX_TEXT);
                if (c.text == null) return "no text";
                controls.add(c);
                return null;
            case ACTION: {
                c.request = p.getProperty(at + "request", "").trim();
                if (c.label == null) return "no label";
                if (!c.request.matches("[a-z0-9_]{1,32}")) return "its request isn't a verb Tilepad sends";
                if (!named) return "an action needs the page's results= for its answers";
                c.confirmTitle = text(p.getProperty(at + "confirm.title"), MAX_LABEL);
                c.confirmText = text(p.getProperty(at + "confirm.text"), MAX_TEXT);
                c.confirmButton = text(p.getProperty(at + "confirm.button"), 40);
                Map<String, String> results = new LinkedHashMap<>();
                for (String key : p.stringPropertyNames()) {
                    if (!key.startsWith(at + "result.")) continue;
                    String word = key.substring((at + "result.").length()), t = text(p.getProperty(key), MAX_TEXT);
                    if (word.matches("[a-z0-9_]{1,32}") && t != null) results.put(word, t);
                }
                c.results = Collections.unmodifiableMap(results);
                Integer t = integer(p.getProperty(at + "timeout", "15"));
                if (t == null || t < 1 || t > 120) return "its timeout isn't 1 to 120 seconds";
                c.timeoutS = t;
                controls.add(c);
                return null;
            }
            default:
                break;
        }
        c.prop = p.getProperty(at + "prop", "").trim();
        if (!propName(c.prop) || !c.prop.startsWith("persist.")) return "its prop isn't a persist. property Tilepad takes";
        if (!props.add(c.prop)) return "its prop is already set by another control";
        // persist.<word>.<key>: the key is what follows the package's own word
        int word = c.prop.indexOf('.', "persist.".length());
        c.key = p.getProperty(at + "key", word > 0 ? c.prop.substring(word + 1) : "").trim();
        if (!c.key.matches("[a-z0-9_][a-z0-9_.]{0,63}")) return "no key for its ctl.sh";
        String def = p.getProperty(at + "default", "").trim();
        switch (c.type) {
            case TOGGLE: {
                c.on = p.getProperty(at + "on", "1").trim();
                c.off = p.getProperty(at + "off", "0").trim();
                if (c.label == null) return "no label";
                if (!value(c.on) || !value(c.off) || c.on.equals(c.off)) return "its on and off values aren't two values Tilepad sends";
                if (!def.isEmpty() && !def.equals(c.on) && !def.equals(c.off)) return "its default isn't its on or off value";
                c.def = def.isEmpty() ? c.off : def;
                break;
            }
            case STEPPER:
            case TEMPERATURE: {
                Integer min = integer(p.getProperty(at + "min")), max = integer(p.getProperty(at + "max"));
                Integer step = integer(p.getProperty(at + "step", "1"));
                if (c.label == null) return "no label";
                if (min == null || max == null || step == null || min >= max || step <= 0 || step > max - min)
                    return "its min, max and step don't make a range";
                c.min = min; c.max = max; c.step = step;
                if (c.type == Type.STEPPER) {
                    Integer d = def.isEmpty() ? min : integer(def);
                    if (d == null || d < min || d > max) return "its default isn't in its range";
                    c.def = String.valueOf(d);
                    String unit = text(p.getProperty(at + "unit", ""), 12);
                    c.unit = unit == null ? "" : unit;
                    c.zero = text(p.getProperty(at + "zero"), 20);
                    Integer of = integer(p.getProperty(at + "of", "0"));
                    c.of = of == null || of < 0 ? 0 : of;
                } else {
                    Double d = def.isEmpty() ? Double.valueOf(min) : celsius(def);
                    if (d == null || d < min || d > max) return "its default isn't in its range";
                    c.def = formatC(d);
                    c.unitprop = p.getProperty(at + "unitprop", "").trim();
                    if (!c.unitprop.isEmpty() && !propName(c.unitprop)) return "its unitprop isn't a property Tilepad reads";
                }
                break;
            }
            case COLOR: {
                c.palette = text(p.getProperty(at + "palette"), MAX_LABEL);
                if (c.label == null) return "no label";
                if (c.palette == null || palettes.isEmpty()) return "no palette";
                if (Template.parse(c.palette).size() == 1 && Template.parse(c.palette).get(0).prop == null && !palettes.containsKey(c.palette))
                    return "its palette isn't one of the page's";
                c.def = def.toUpperCase(Locale.US);
                if (!c.def.matches("[0-9A-F]{6}") || !inSomePalette(palettes, c.def)) return "its default isn't one of the palette's colors";
                break;
            }
            default: { // CHOICES
                List<Option> options = new ArrayList<>();
                Set<String> values = new LinkedHashSet<>();
                for (int m : numbers(p, at + "option.", MAX_OPTIONS, null)) {
                    String[] parts = p.getProperty(at + "option." + m, "").split("\\|", 3);
                    String v = parts[0].trim(), l = parts.length > 1 ? text(parts[1], MAX_LABEL) : null;
                    if (!value(v) || l == null || !values.add(v)) return "option " + m + " needs value|label, each value once";
                    options.add(new Option(v, l, parts.length > 2 ? text(parts[2], MAX_TEXT) : null));
                }
                if (options.size() < 2) return "fewer than two options";
                c.def = def.isEmpty() ? options.get(0).value : def;
                if (!values.contains(c.def)) return "its default isn't one of its options";
                String layout = p.getProperty(at + "layout", "").trim();
                c.horizontal = "horizontal".equals(layout);
                c.vertical = "vertical".equals(layout);
                c.options = Collections.unmodifiableList(options);
            }
        }
        controls.add(c);
        return null;
    }

    private static boolean inSomePalette(Map<String, Palette> palettes, String color) {
        for (Palette pal : palettes.values()) if (pal.has(color)) return true;
        return false;
    }

    /** The numbers N of prefix.N.… (or prefix.N) entries, in order, at most max of them. */
    private static List<Integer> numbers(Properties p, String prefix, int max, List<String> problems) {
        TreeMap<Integer, Boolean> found = new TreeMap<>();
        for (String name : p.stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            String rest = name.substring(prefix.length());
            int dot = rest.indexOf('.');
            String number = dot < 0 ? rest : rest.substring(0, dot);
            if (number.matches("[0-9]{1,3}")) found.put(Integer.parseInt(number), true);
        }
        List<Integer> out = new ArrayList<>(found.keySet());
        if (out.size() > max) {
            if (problems != null) problems.add(prefix + ": only the first " + max + " are used");
            out = out.subList(0, max);
        }
        return out;
    }

    /** The control that sets prop, or null. */
    public Control setting(String prop) {
        for (Control c : controls) if (c.sets() && c.prop.equals(prop)) return c;
        return null;
    }

    /** The action control that sends request verb, or null. */
    public Control action(String verb) {
        for (Control c : controls) if (c.type == Type.ACTION && c.request.equals(verb)) return c;
        return null;
    }

    /** Whether value is one this page would send for its control c. */
    public boolean allows(Control c, String value) {
        if (c == null || !c.sets() || !value(value)) return false;
        switch (c.type) {
            case TOGGLE: return value.equals(c.on) || value.equals(c.off);
            case STEPPER: {
                Integer v = integer(value);
                return v != null && v >= c.min && v <= c.max;
            }
            case TEMPERATURE: {
                Double v = celsius(value);
                return v != null && v >= c.min && v <= c.max && value.equals(formatC(v));
            }
            case COLOR: return value.matches("[0-9A-F]{6}") && inSomePalette(palettes, value);
            default:
                for (Option o : c.options) if (o.value.equals(value)) return true;
                return false;
        }
    }

    /** The palette color control c chooses from now, or null when its template names none. */
    public Palette palette(Control c, Properties read) {
        if (c.palette == null) return null;
        return palettes.get(fill(c.palette, read).trim());
    }

    /** What a property shows as: what was read, or for a setting that isn't set, its control's default. */
    public String value(Properties read, String prop) {
        String v = read.getProperty(prop);
        if (v != null && !v.isEmpty()) return v;
        Control c = setting(prop);
        return c == null || c.def == null ? "" : c.def;
    }

    /** Every property the page shows or tests, for a read. */
    public Set<String> reads() {
        Set<String> out = new LinkedHashSet<>();
        for (Status s : status) { out.addAll(Template.props(s.text)); out.addAll(s.when.props()); }
        for (Control c : controls) {
            if (c.prop != null) out.add(c.prop);
            out.addAll(c.when.props());
            if (c.text != null) out.addAll(Template.props(c.text));
            if (c.palette != null) out.addAll(Template.props(c.palette));
            if (c.unitprop != null && !c.unitprop.isEmpty()) out.add(c.unitprop);
        }
        if (results != null) out.add(results);
        return out;
    }

    /** Whether when holds for what's read (unset settings counting as their defaults). */
    public boolean holds(Condition when, Properties read) {
        for (Condition.Clause c : when.clauses) if (!c.holds(value(read, c.prop))) return false;
        return true;
    }

    /** template filled in from what's read. */
    public String fill(String template, Properties read) {
        StringBuilder out = new StringBuilder();
        for (Template.Part part : Template.parse(template)) out.append(part.prop == null ? part.text : part.word(value(read, part.prop)));
        return out.toString();
    }

    /** A stepper's reading for value v: its zero word at min, "v / of", or v and its unit. */
    public static String reading(Control c, int v) {
        if (c.zero != null && v == c.min) return c.zero;
        if (c.of > 0) return v + " / " + c.of;
        return v + c.unit;
    }

    /** A temperature control's reading for stored °C in the unit fahrenheit says, e.g. "41 °F". */
    public static String temperature(double c, boolean fahrenheit) {
        return fahrenheit ? Math.round(c * 9 / 5 + 32) + " °F" : Math.round(c) + " °C";
    }

    /** One step (by +1 or -1) of a temperature control from stored °C, a degree of the shown unit, within [min, max] °C. */
    public static String stepTemperature(Control c, double now, int by, boolean fahrenheit) {
        double next;
        if (fahrenheit) next = (Math.round(now * 9 / 5 + 32) + by - 32) * 5 / 9.0;
        else next = Math.round(now) + by;
        return formatC(Math.max(c.min, Math.min(c.max, next)));
    }

    static String formatC(double c) { return String.format(Locale.US, "%.1f", Math.round(c * 10) / 10.0); }

    private static Double celsius(String s) {
        if (s == null || !s.trim().matches("-?[0-9]{1,3}(\\.[0-9])?")) return null;
        return Double.valueOf(s.trim());
    }

    static boolean propName(String s) {
        return s != null && s.length() <= MAX_PROP && s.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+");
    }

    /** A value Tilepad sends to a package: letters, digits and . _ : + -, at most 64 of them. */
    static boolean value(String s) { return s != null && s.matches("[A-Za-z0-9._:+-]{1,64}"); }

    private static Integer integer(String s) {
        if (s == null || !s.trim().matches("-?[0-9]{1,6}")) return null;
        return Integer.valueOf(s.trim());
    }

    /** Text for the page: trimmed, without control characters other than new lines; null when empty or too long. */
    private static String text(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.length() > max) return null;
        StringBuilder out = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '\n' || ch >= ' ' && ch != 0x7f) out.append(ch);
        }
        return out.toString();
    }

    /** A condition: clauses that must all hold. Empty: always. */
    public static final class Condition {
        static final Condition ALWAYS = new Condition(Collections.<Clause>emptyList());
        final List<Clause> clauses;
        private Condition(List<Clause> clauses) { this.clauses = clauses; }

        static final class Clause {
            final String prop, op;
            final List<String> values;
            Clause(String prop, String op, List<String> values) { this.prop = prop; this.op = op; this.values = values; }
            boolean holds(String v) {
                switch (op) {
                    case "=": return v.equals(values.get(0));
                    case "!=": return !v.equals(values.get(0));
                    case "in": return values.contains(v);
                    case "set": return !v.isEmpty();
                    default: return v.isEmpty(); // unset
                }
            }
        }

        /** when as a condition; ALWAYS for none; null when Tilepad can't read it. */
        static Condition parse(String when) {
            if (when == null || when.trim().isEmpty()) return ALWAYS;
            List<Clause> out = new ArrayList<>();
            for (String clause : when.trim().split("\\s+and\\s+")) {
                String[] w = clause.trim().split("\\s+", 3);
                if (w.length < 2 || !propName(w[0])) return null;
                if (w.length == 2 && (w[1].equals("set") || w[1].equals("unset"))) { out.add(new Clause(w[0], w[1], Collections.<String>emptyList())); continue; }
                if (w.length != 3) return null;
                if (!w[1].equals("=") && !w[1].equals("!=") && !w[1].equals("in")) return null;
                List<String> values = new ArrayList<>();
                for (String v : (w[1].equals("in") ? w[2].split(",") : new String[]{w[2]})) {
                    if (!value(v.trim())) return null;
                    values.add(v.trim());
                }
                out.add(new Clause(w[0], w[1], values));
            }
            return new Condition(out);
        }

        Set<String> props() {
            Set<String> out = new LinkedHashSet<>();
            for (Clause c : clauses) out.add(c.prop);
            return out;
        }
    }

    /** Text with {prop} and {prop|value=word,…,*=word} places. Anything else in braces stays as written. */
    static final class Template {
        static final class Part {
            final String text, prop;
            final List<String[]> words;
            Part(String text, String prop, List<String[]> words) { this.text = text; this.prop = prop; this.words = words; }
            String word(String v) {
                String other = null;
                for (String[] w : words) {
                    if (w[0].equals(v)) return w[1];
                    if (w[0].equals("*")) other = w[1];
                }
                return other != null ? other : v;
            }
        }

        static List<Part> parse(String template) {
            List<Part> out = new ArrayList<>();
            if (template == null) return out;
            int i = 0;
            while (i < template.length()) {
                int open = template.indexOf('{', i), close = open < 0 ? -1 : template.indexOf('}', open);
                if (open < 0 || close < 0) { out.add(new Part(template.substring(i), null, null)); break; }
                if (open > i) out.add(new Part(template.substring(i, open), null, null));
                String inside = template.substring(open + 1, close);
                int bar = inside.indexOf('|');
                String prop = (bar < 0 ? inside : inside.substring(0, bar)).trim();
                List<String[]> words = new ArrayList<>();
                boolean ok = propName(prop);
                if (ok && bar >= 0) for (String pair : inside.substring(bar + 1).split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) { ok = false; break; }
                    words.add(new String[]{pair.substring(0, eq).trim(), pair.substring(eq + 1).trim()});
                }
                out.add(ok ? new Part(null, prop, words) : new Part(template.substring(open, close + 1), null, null));
                i = close + 1;
            }
            return out;
        }

        static Set<String> props(String template) {
            Set<String> out = new LinkedHashSet<>();
            for (Part p : parse(template)) if (p.prop != null) out.add(p.prop);
            return out;
        }
    }
}
