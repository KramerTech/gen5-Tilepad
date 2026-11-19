package com.tilepad.tweakcore;

import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddonPageTest {
    static final String EXAMPLE = "schema=1\n"
        + "title=Example Package\n"
        + "help=Shows what a package page can do.\\n\\nMode chooses how it runs.\n"
        + "status.1.when=eg.exa.gate = fail\n"
        + "status.1.problem=1\n"
        + "status.1.text=Not supported on this firmware.\n"
        + "status.2.when=eg.exa.state in slow,steady,fast\n"
        + "status.2.text=Now: {eg.exa.pace|slow=slowly,steady=steadily,*=running} · {eg.exa.count} steps\n"
        + "control.1.type=toggle\n"
        + "control.1.prop=persist.eg.exa\n"
        + "control.1.label=Example Switch\n"
        + "control.2.type=stepper\n"
        + "control.2.prop=persist.eg.exa_vol\n"
        + "control.2.label=Volume\n"
        + "control.2.min=0\ncontrol.2.max=100\ncontrol.2.step=5\ncontrol.2.default=50\n"
        + "control.2.when=persist.eg.exa = 1\n"
        + "control.3.type=heading\ncontrol.3.label=Modes\n"
        + "control.10.type=choices\n"
        + "control.10.prop=persist.eg.exa_mode\n"
        + "control.10.default=calm\n"
        + "control.10.option.1=calm|Calm|One step at a time\n"
        + "control.10.option.2=busy|Busy\n"
        + "control.10.option.3=still|Still\n";

    @Test public void readsAChoicesLayout() throws Exception {
        String base = "schema=1\ntitle=T\ncontrol.1.type=choices\ncontrol.1.prop=persist.eg.m\n"
            + "control.1.option.1=a|Manual\ncontrol.1.option.2=b|Weather\n";
        AddonPage.Control vertical = AddonPage.parse("x.y", base + "control.1.layout=vertical\n").controls.get(0);
        assertTrue(vertical.vertical);
        assertFalse(vertical.horizontal);
        AddonPage.Control horizontal = AddonPage.parse("x.y", base + "control.1.layout=horizontal\n").controls.get(0);
        assertTrue(horizontal.horizontal);
        assertFalse(horizontal.vertical);
        AddonPage.Control unset = AddonPage.parse("x.y", base).controls.get(0);
        assertFalse("no layout: Tilepad decides", unset.vertical || unset.horizontal);
    }

    @Test public void readsAPage() throws Exception {
        AddonPage p = AddonPage.parse("example.page", EXAMPLE);
        assertEquals("Example Package", p.title);
        assertEquals("Shows what a package page can do.\n\nMode chooses how it runs.", p.help);
        assertEquals("controls in number order, 10 after 3", 4, p.controls.size());
        assertEquals(AddonPage.Type.CHOICES, p.controls.get(3).type);
        assertEquals("exa_mode", p.controls.get(3).key);
        assertEquals(3, p.controls.get(3).options.size());
        assertEquals("One step at a time", p.controls.get(3).options.get(0).note);
        assertNull(p.controls.get(3).options.get(1).note);
        assertEquals("0", p.controls.get(0).def);
        assertTrue(p.problems.isEmpty());
        assertTrue(p.reads().containsAll(java.util.Arrays.asList("persist.eg.exa", "persist.eg.exa_vol", "persist.eg.exa_mode",
            "eg.exa.gate", "eg.exa.state", "eg.exa.pace", "eg.exa.count")));
    }

    @Test public void conditionsAndTemplatesUseDefaultsForUnsetSettings() throws Exception {
        AddonPage p = AddonPage.parse("example.page", EXAMPLE);
        Properties read = new Properties();
        assertFalse("volume hidden while off (unset = default 0)", p.holds(p.controls.get(1).when, read));
        read.setProperty("persist.eg.exa", "1");
        assertTrue(p.holds(p.controls.get(1).when, read));
        assertEquals("50", p.value(read, "persist.eg.exa_vol"));
        assertEquals("calm", p.value(read, "persist.eg.exa_mode"));
        assertFalse(p.holds(p.status.get(1).when, read));
        read.setProperty("eg.exa.state", "steady");
        read.setProperty("eg.exa.pace", "steady");
        read.setProperty("eg.exa.count", "2");
        assertTrue(p.holds(p.status.get(1).when, read));
        assertEquals("Now: steadily · 2 steps", p.fill(p.status.get(1).text, read));
        read.setProperty("eg.exa.pace", "sprint");
        assertEquals("* covers the rest", "Now: running · 2 steps", p.fill(p.status.get(1).text, read));
        assertEquals("unmapped is the value itself", "sprint · 2 steps", p.fill("{eg.exa.pace|slow=slowly} · {eg.exa.count} steps", read));
        assertEquals("braces that aren't a place stay", "a {not a prop} b", p.fill("a {not a prop} b", read));
    }

    @Test public void onlyItsOwnValuesAreAllowed() throws Exception {
        AddonPage p = AddonPage.parse("example.page", EXAMPLE);
        AddonPage.Control on = p.setting("persist.eg.exa"), vol = p.setting("persist.eg.exa_vol"), style = p.setting("persist.eg.exa_mode");
        assertTrue(p.allows(on, "1"));
        assertFalse(p.allows(on, "2"));
        assertTrue(p.allows(vol, "100"));
        assertFalse(p.allows(vol, "101"));
        assertFalse(p.allows(vol, "5;reboot"));
        assertTrue(p.allows(style, "busy"));
        assertFalse(p.allows(style, "frantic"));
        assertFalse("a heading sets nothing", p.allows(p.controls.get(2), "x"));
        assertNull(p.setting("persist.eg.other"));
    }

    @Test public void skipsBadEntriesAndSaysWhy() throws Exception {
        AddonPage p = AddonPage.parse("x.pkg", "schema=1\ntitle=X\n"
            + "control.1.type=slider\n"
            + "control.2.type=toggle\ncontrol.2.prop=eg.not_persist\ncontrol.2.label=A\n"
            + "control.3.type=toggle\ncontrol.3.prop=persist.eg.a\ncontrol.3.label=A\ncontrol.3.on=1;rm\n"
            + "control.4.type=stepper\ncontrol.4.prop=persist.eg.b\ncontrol.4.label=B\ncontrol.4.min=5\ncontrol.4.max=1\n"
            + "control.5.type=choices\ncontrol.5.prop=persist.eg.c\ncontrol.5.option.1=only|Only\n"
            + "control.6.type=toggle\ncontrol.6.prop=persist.d\ncontrol.6.label=D\n"
            + "control.7.type=note\ncontrol.7.text=Fine\ncontrol.7.when=persist.eg.a ~ 1\n"
            + "control.8.type=toggle\ncontrol.8.prop=persist.eg.e\ncontrol.8.label=E\n"
            + "control.9.type=toggle\ncontrol.9.prop=persist.eg.e\ncontrol.9.label=E again\n");
        assertEquals("only control 8 is usable", 1, p.controls.size());
        assertEquals("persist.eg.e", p.controls.get(0).prop);
        assertEquals(p.problems.toString(), 8, p.problems.size());
        assertTrue(p.problems.get(0).startsWith("control 1: type \"slider\""));
        assertTrue("a persist. property without a second word needs a key", p.problems.toString().contains("control 6: no key"));
    }

    @Test public void keyCanBeGiven() throws Exception {
        AddonPage p = AddonPage.parse("other", "schema=1\ntitle=O\ncontrol.1.type=toggle\ncontrol.1.prop=persist.other.on\ncontrol.1.key=on\ncontrol.1.label=On\n");
        assertEquals("on", p.controls.get(0).key);
    }

    @Test public void unusablePages() {
        assertInvalid("example.page", "title=No schema\n");
        assertInvalid("example.page", "schema=2\ntitle=Later\n");
        assertInvalid("example.page", "schema=1\n");
        assertInvalid("../tilepad", "schema=1\ntitle=T\n");
        assertInvalid("Example", "schema=1\ntitle=T\n");
        assertInvalid("a'b", "schema=1\ntitle=T\n");
        StringBuilder big = new StringBuilder("schema=1\ntitle=T\n");
        while (big.length() <= AddonPage.MAX_FILE) big.append("# padding padding padding padding\n");
        assertInvalid("example.page", big.toString());
    }

    static final String COLORS = "schema=1\ntitle=Example Colors\nresults=eg.res.exa\n"
        + "palette.warm=FF7D73,FFB000,ff4fa0\npalette.warm.names=Coral,Amber,Pink\n"
        + "palette.cool=1043F4,00B4FF\n"
        + "control.1.type=color\ncontrol.1.prop=persist.eg.exa_day\ncontrol.1.label=Day color\ncontrol.1.default=ff7d73\n"
        + "control.1.palette={eg.exa.set|cool=cool,*=warm}\n"
        + "control.2.type=stepper\ncontrol.2.prop=persist.eg.exa_lv\ncontrol.2.label=Brightness\ncontrol.2.min=0\ncontrol.2.max=10\n"
        + "control.2.default=7\ncontrol.2.zero=Off\ncontrol.2.of=10\n"
        + "control.3.type=temperature\ncontrol.3.prop=persist.eg.exa_cold\ncontrol.3.label=Cold threshold\ncontrol.3.min=-30\n"
        + "control.3.max=40\ncontrol.3.default=5\ncontrol.3.unitprop=eg.exa.unit\n"
        + "control.4.type=action\ncontrol.4.label=Restore My Setting\ncontrol.4.request=restore\n"
        + "control.4.confirm.title=Restore My Setting?\ncontrol.4.confirm.button=Restore My Setting\n"
        + "control.4.result.restored=Restored.\ncontrol.4.result.failed=Couldn't restore.\ncontrol.4.timeout=30\n";

    @Test public void colorsComeFromThePalettesTheTemplateNames() throws Exception {
        AddonPage p = AddonPage.parse("example.colors", COLORS);
        assertTrue(p.problems.toString(), p.problems.isEmpty());
        AddonPage.Control day = p.controls.get(0);
        assertEquals("FF7D73", day.def);
        Properties read = new Properties();
        assertEquals("Coral", p.palette(day, read).nameOf("FF7D73"));
        assertEquals("FF4FA0", p.palette(day, read).colors.get(2));
        read.setProperty("eg.exa.set", "cool");
        assertEquals("#1043F4", p.palette(day, read).nameOf("1043F4"));
        assertTrue(p.allows(day, "00B4FF"));
        assertFalse("not on any palette", p.allows(day, "123456"));
        assertFalse("six digits, upper case", p.allows(day, "00b4ff"));
        assertTrue(p.reads().containsAll(java.util.Arrays.asList("eg.exa.set", "eg.exa.unit", "eg.res.exa")));
    }

    @Test public void stepperReadingsAndTemperatures() throws Exception {
        AddonPage p = AddonPage.parse("example.colors", COLORS);
        AddonPage.Control lv = p.controls.get(1), cold = p.controls.get(2);
        assertEquals("Off", AddonPage.reading(lv, 0));
        assertEquals("7 / 10", AddonPage.reading(lv, 7));
        assertEquals("5.0", cold.def);
        assertEquals("41 °F", AddonPage.temperature(5.0, true));
        assertEquals("5 °C", AddonPage.temperature(5.0, false));
        assertEquals("one °F up from 41 °F is 42 °F", "5.6", AddonPage.stepTemperature(cold, 5.0, 1, true));
        assertEquals("42 °F", AddonPage.temperature(5.6, true));
        assertEquals("6.0", AddonPage.stepTemperature(cold, 5.0, 1, false));
        assertEquals("held at max", "40.0", AddonPage.stepTemperature(cold, 40.0, 1, false));
        assertTrue(p.allows(cold, "5.6"));
        assertFalse(p.allows(cold, "5.65"));
        assertFalse(p.allows(cold, "41.0"));
    }

    @Test public void actionsNeedANameAndAVerb() throws Exception {
        AddonPage p = AddonPage.parse("example.colors", COLORS);
        AddonPage.Control restore = p.action("restore");
        assertEquals(30, restore.timeoutS);
        assertEquals("Restored.", restore.results.get("restored"));
        assertEquals("Restore My Setting?", restore.confirmTitle);
        assertNull(restore.confirmText);
        assertNull(p.action("reboot"));
        AddonPage q = AddonPage.parse("x", "schema=1\ntitle=X\ncontrol.1.type=action\ncontrol.1.label=Go\ncontrol.1.request=go\n"
            + "control.2.type=action\ncontrol.2.label=Bad\ncontrol.2.request=go;reboot\n");
        assertTrue(q.controls.isEmpty());
        assertTrue(q.problems.get(0).contains("needs the page's results="));
        assertTrue(q.problems.get(1).contains("isn't a verb"));
    }

    @Test public void resultsIsAPropertyTheTilepadReads() throws Exception {
        AddonPage p = AddonPage.parse("x", "schema=1\ntitle=X\nresults=xyz.res\ncontrol.1.type=action\ncontrol.1.label=Go\ncontrol.1.request=go\n");
        assertEquals("xyz.res", p.results);
        assertEquals(1, p.controls.size());
        assertTrue(p.reads().contains("xyz.res"));
        for (String bad : new String[]{"persist.xyz.res", "noDot", "xyz.res.a_name_far_too_long_for_it", "xyz.res;reboot"}) {
            AddonPage q = AddonPage.parse("x", "schema=1\ntitle=X\nresults=" + bad + "\ncontrol.1.type=action\ncontrol.1.label=Go\ncontrol.1.request=go\n");
            assertNull(bad, q.results);
            assertTrue(bad, q.controls.isEmpty());
            assertTrue(bad, q.problems.get(0).startsWith("results:"));
        }
    }

    @Test public void aSettingsKeyIsWhatFollowsItsPropertysSecondWord() throws Exception {
        AddonPage p = AddonPage.parse("x", "schema=1\ntitle=X\n"
            + "control.1.type=toggle\ncontrol.1.prop=persist.xyz.on\ncontrol.1.label=On\n"
            + "control.2.type=toggle\ncontrol.2.prop=persist.xyz.seat.hb\ncontrol.2.label=Heat\n"
            + "control.3.type=toggle\ncontrol.3.prop=persist.xyz.lv\ncontrol.3.key=level\ncontrol.3.label=Level\n"
            + "control.4.type=toggle\ncontrol.4.prop=persist.nodot\ncontrol.4.label=No Key\n");
        assertEquals("on", p.controls.get(0).key);
        assertEquals("seat.hb", p.controls.get(1).key);
        assertEquals("level", p.controls.get(2).key);
        assertEquals(3, p.controls.size());
        assertTrue(p.problems.get(0).contains("no key"));
    }

    @Test public void theDocsExampleUsesEveryKindOfControl() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile();
        String example = "docs/examples/package-page/" + AddonPage.FILE;
        while (dir != null && !new java.io.File(dir, example).isFile()) dir = dir.getParentFile();
        assertNotNull("the example page is in the repository", dir);
        byte[] bytes = java.nio.file.Files.readAllBytes(new java.io.File(dir, example).toPath());
        AddonPage p = AddonPage.parse("example.page", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(p.problems.toString(), p.problems.isEmpty());
        java.util.Set<AddonPage.Type> types = java.util.EnumSet.noneOf(AddonPage.Type.class);
        for (AddonPage.Control c : p.controls) types.add(c.type);
        assertEquals(java.util.EnumSet.allOf(AddonPage.Type.class), types);
    }

    private static void assertInvalid(String id, String text) {
        try {
            AddonPage.parse(id, text);
            fail("parsed " + id + ": " + text);
        } catch (AddonPage.Invalid expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
