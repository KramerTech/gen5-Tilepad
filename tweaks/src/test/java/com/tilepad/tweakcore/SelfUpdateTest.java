package com.tilepad.tweakcore;

import static org.junit.Assert.*;

import java.io.*;
import java.security.*;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Test;

public class SelfUpdateTest {
    private File dir, downloads;
    private KeyPair key;

    @Before public void setUp() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("self-update").toFile();
        downloads = java.nio.file.Files.createTempDirectory("downloads").toFile();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        key = g.generateKeyPair();
    }

    @Test public void theEmbeddedCertificateIsTheOneThatSignsTheApks() throws Exception {
        File pin = new File("tools/signing-cert.sha256");
        if (!pin.isFile()) pin = new File("../tools/signing-cert.sha256");
        String want = new String(Files.read(pin), "UTF-8").replaceAll("\\s", "");
        assertEquals(want, Files.hash(SelfUpdate.releaseCertificate().getEncoded()));
    }

    @Test public void versionsCompareNumberByNumber() {
        assertEquals("1.2.0", SelfUpdate.version("v1.2.0"));
        assertEquals("1.1.10", SelfUpdate.version(" 1.1.10 "));
        assertNull(SelfUpdate.version("v1.2.0-rc1"));
        assertNull(SelfUpdate.version("latest"));
        assertNull(SelfUpdate.version(null));
        assertTrue(SelfUpdate.compare("1.1.10", "1.1.9") > 0);
        assertTrue(SelfUpdate.compare("1.1.9", "1.2.0") < 0);
        assertEquals(0, SelfUpdate.compare("1.2", "1.2.0"));
    }

    @Test public void theDefaultRouteIsTheLowestMetricOneThatIsUp() {
        String header = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n";
        assertEquals("wlan0", SelfUpdate.defaultRoute(header
            + "rmnet0\t00000000\t0101A8C0\t0003\t0\t0\t10\t00000000\t0\t0\t0\n"
            + "rmnet0\t0010AC0A\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n"
            + "wlan0\t00000000\t0100A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0\n"));
        // Down (flags without RTF_UP), or only a subnet: no default route.
        assertNull(SelfUpdate.defaultRoute(header + "wlan0\t00000000\t0100A8C0\t0002\t0\t0\t0\t00000000\t0\t0\t0\n"));
        assertNull(SelfUpdate.defaultRoute(header + "wlan0\t0000A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n"));
        assertEquals("eth0", SelfUpdate.defaultRoute(header + "eth0\t00000000\t0202000A\t0003\t0\t0\t0\t00000000\t0\t0\t0\n"));
    }

    @Test public void aSignedNewerTilepadPackageIsStaged() throws Exception {
        File zip = pkg("id=tilepad\npackage-version=1.2.0\n");
        SelfUpdate.Item item = tilepad(zip, sign(zip, key.getPrivate()));
        SelfUpdate.stage(item, dir, "1.1.10", key.getPublic());
        assertEquals("1.2.0", item.version); assertEquals("1.1.10", item.from); assertEquals("Tilepad", item.name);
        assertArrayEquals(Files.read(zip), Files.read(new File(dir, SelfUpdate.ZIP)));
        assertTrue(new File(dir, SelfUpdate.SIGNATURE).isFile());
        assertFalse(item.reboots);
    }

    @Test public void aDownloadWithoutTheReleaseSignatureIsRefusedAndRemoved() throws Exception {
        File zip = pkg("id=tilepad\npackage-version=1.2.0\n"), other = pkg("id=tilepad\npackage-version=1.2.1\n");
        refused(tilepad(zip, sign(other, key.getPrivate())), "1.1.10", "isn't signed with");
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        refused(tilepad(zip, sign(zip, g.generateKeyPair().getPrivate())), "1.1.10", "isn't signed with");
        File garbage = new File(downloads, "garbage.sig"); Files.write(garbage, new byte[256]);
        refused(tilepad(zip, garbage), "1.1.10", "isn't signed with");
    }

    @Test public void onlyANewerTilepadPackageIsStaged() throws Exception {
        File same = pkg("id=tilepad\npackage-version=1.1.10\n");
        refused(tilepad(same, sign(same, key.getPrivate())), "1.1.10", "already installed");
        File older = pkg("id=tilepad\npackage-version=1.1.9\n");
        refused(tilepad(older, sign(older, key.getPrivate())), "1.1.10", "Tilepad 1.1.10 is already installed; the download is 1.1.9.");
        File other = pkg("id=adb.wifi\npackage-version=9.0\n");
        refused(tilepad(other, sign(other, key.getPrivate())), "1.1.10", "isn't a Tilepad package");
        File none = new File(downloads, "none.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(none))) { z.putNextEntry(new ZipEntry("README")); z.write(1); }
        refused(tilepad(none, sign(none, key.getPrivate())), "1.1.10", "isn't a Tilepad package");
        File garbage = new File(downloads, "garbage.zip"); Files.write(garbage, new byte[]{1, 2, 3});
        refused(tilepad(garbage, sign(garbage, key.getPrivate())), "1.1.10", "isn't a Tilepad package");
        refused(tilepad(new File(downloads, "missing.zip"), new File(downloads, "missing.sig")), "1.1.10", "missing");
    }

    @Test public void anotherPackageMustMatchGitHubsChecksumAndBeTheOneNamed() throws Exception {
        File zip = pkg("id=dashcam\nname=Rear Dashcam\npackage-version=1.2.8\ndepends=media.codecs@>=2.0,!hmgapi\n");
        SelfUpdate.Item item = new SelfUpdate.Item("dashcam", zip, Files.hash(zip).toUpperCase(), null);
        SelfUpdate.stage(item, dir, "1.2.7", null);
        assertEquals("Rear Dashcam", item.name); assertEquals("1.2.8", item.version);
        assertEquals(java.util.Arrays.asList("media.codecs"), item.depends);
        assertTrue(new File(dir, "dashcam.zip").isFile());
        new File(dir, "dashcam.zip").delete();
        // Not installed here: any version installs fresh.
        item = new SelfUpdate.Item("dashcam", zip, Files.hash(zip), null);
        SelfUpdate.stage(item, dir, null, null);
        assertNull(item.from); assertEquals("1.2.8", item.version);
        new File(dir, "dashcam.zip").delete();

        refused(new SelfUpdate.Item("dashcam", zip, Files.hash(new byte[]{1}), null), "1.2.7", "doesn't match GitHub's checksum");
        refused(new SelfUpdate.Item("smarttube", zip, Files.hash(zip), null), "1.0", "isn't the smarttube package");
        refused(new SelfUpdate.Item("dashcam", zip, Files.hash(zip), null), "1.2.8", "Rear Dashcam 1.2.8 is already installed; the download is 1.2.8.");
    }

    @Test public void aPackageIsTakenOutOfAUsbBundle() throws Exception {
        File inner = pkg("id=wireless.carplay.aa\nname=Evergreen\npackage-version=1.2.0\npostinstall=scripts/post.sh\n"),
            bundle = new File(downloads, "bundle.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(bundle))) {
            z.putNextEntry(new ZipEntry("NEMESIS.data")); z.write("[nemesis]\ntype=install\npath=packages/wireless.carplay.aa.zip\n".getBytes("UTF-8"));
            z.putNextEntry(new ZipEntry("packages/wireless.carplay.aa.zip")); z.write(Files.read(inner));
        }
        SelfUpdate.Item item = new SelfUpdate.Item("wireless.carplay.aa", bundle, Files.hash(bundle), "packages/wireless.carplay.aa.zip");
        SelfUpdate.stage(item, dir, "1.1.0", null);
        assertArrayEquals(Files.read(inner), Files.read(new File(dir, "wireless.carplay.aa.zip")));
        assertFalse(new File(dir, "wireless.carplay.aa.download").exists());
        assertTrue(item.reboots);
        new File(dir, "wireless.carplay.aa.zip").delete();
        refused(new SelfUpdate.Item("wireless.carplay.aa", bundle, Files.hash(bundle), "packages/other.zip"), "1.1.0", "has no packages/other.zip");
        refused(new SelfUpdate.Item("wireless.carplay.aa", bundle, Files.hash(bundle), "../x.zip"), "1.1.0", "isn't a NEMESIS package");
    }

    @Test public void onlyARealRebootCommandCounts() {
        assertTrue(SelfUpdate.reboots("sync\nexec /system/bin/reboot\n"));
        assertTrue(SelfUpdate.reboots("[ -n \"$X\" ] && reboot"));
        assertTrue(SelfUpdate.reboots("if true; then reboot; fi"));
        assertTrue(SelfUpdate.reboots("setprop sys.powerctl reboot"));
        assertTrue(SelfUpdate.reboots("  svc power reboot"));
        assertFalse(SelfUpdate.reboots("log \"all parts installed - reboot the head unit\"\n# reboot\ntoast 'Reboot, then try again.'\n"));
        assertFalse(SelfUpdate.reboots("echo done (reboot comes at the end)\nexit 0   # bundle: one reboot at the end\n"));
    }

    @Test public void installsGoInAnOrderThatLetsEachOneFinish() {
        SelfUpdate.Item tp = item("tilepad"), evergreen = item("wireless.carplay.aa"), dashcam = item("dashcam"), codecs = item("media.codecs");
        evergreen.reboots = true; dashcam.depends.add("media.codecs");
        assertEquals("media.codecs,dashcam,tilepad,wireless.carplay.aa", ids(SelfUpdate.order(java.util.Arrays.asList(evergreen, tp, dashcam, codecs))));
        assertEquals("dashcam,tilepad", ids(SelfUpdate.order(java.util.Arrays.asList(tp, dashcam))));
    }

    @Test public void installedPackagesComeFromNemesisRecords() throws Exception {
        java.util.List<SelfUpdate.Package> db = SelfUpdate.packages("usb-handler|USB Handler|1|service|1.16.7|Runs USB\n"
            + "tilepad|Tilepad|0|application,service|1.2.0|Star-hold\nbad id|x|0|x|1|x\n\ndashcam||0|application|1.2.7|\n"
            + "wireless.carplay.aa|Evergreen|0|application,service\n");
        assertEquals(4, db.size());
        assertNull(db.get(3).version); assertFalse(db.get(3).locked);
        assertTrue(db.get(0).locked);
        assertEquals("1.2.0", db.get(1).version);
        assertEquals("dashcam", db.get(2).name);
        File apps = new File(dir, "apps"); installed(apps, "dashcam", "1.2.7");
        assertEquals("1.2.7", SelfUpdate.installedVersion(apps, "dashcam"));
        assertNull(SelfUpdate.installedVersion(apps, "smarttube"));
    }

    @Test public void anyPackageButNemesisOwnAndTilepadCanBeLinked() throws Exception {
        File apps = new File(dir, "apps"), db = new File(dir, "packages.db"), sources = new File(dir, "sources");
        installed(apps, "dashcam", "1.2.7"); installed(apps, "usb-handler", "1.16.7");
        Files.write(db, "usb-handler|USB Handler|1|service|1.16.7|x\ndashcam|Rear Dashcam|0|application|1.2.7|x\n".getBytes("UTF-8"));
        SelfUpdate.setSource(sources, apps, db, "dashcam", "KramerTech/gen5-RearDashcam");
        assertEquals("KramerTech/gen5-RearDashcam", Files.properties(sources).getProperty("dashcam"));
        linkRefused(sources, apps, db, "usb-handler", "a/b", "part of NEMESIS");
        // Not installed yet: Package Manager installs it fresh.
        SelfUpdate.setSource(sources, apps, db, "smarttube", "KramerTech/gen5-SmartTube");
        assertEquals("KramerTech/gen5-SmartTube", Files.properties(sources).getProperty("smarttube"));
        SelfUpdate.setSource(sources, apps, db, "smarttube", null);
        linkRefused(sources, apps, db, "tilepad", "a/b", "own repository");
        linkRefused(sources, apps, db, "dashcam", "not a repo", "isn't a GitHub repository");
        linkRefused(sources, apps, db, "../x", "a/b", "isn't a NEMESIS package id");
        SelfUpdate.setSource(sources, apps, db, "dashcam", null);
        assertTrue(Files.properties(sources).isEmpty());
        assertTrue(SelfUpdate.validRepo("zpoon/gen5w-Evergreen"));
        assertFalse(SelfUpdate.validRepo("-x/y")); assertFalse(SelfUpdate.validRepo("x/.."));
    }

    @Test public void deleteUninstallsAnyPackageButTilepadAndNemesisOwn() throws Exception {
        File db = new File(dir, "packages.db");
        Files.write(db, "usb-handler|USB Handler|1|service\ndashcam|Rear Dashcam|0|application\ntilepad|Tilepad|0|application\n".getBytes("UTF-8"));
        assertTrue(SelfUpdate.uninstallable(db, "dashcam"));
        // Linked but not installed: only the link goes.
        assertFalse(SelfUpdate.uninstallable(db, "smarttube"));
        for (String[] refused : new String[][]{{"tilepad", "USB uninstall bundle"}, {"usb-handler", "part of NEMESIS"}, {"../x", "isn't a NEMESIS package id"}}) {
            try { SelfUpdate.uninstallable(db, refused[0]); fail(refused[0]); }
            catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains(refused[1])); }
        }
    }

    @Test public void lookedUpOwnersStartWithTheDefaultsAndRefuseBadNames() throws Exception {
        File owners = new File(dir, "owners");
        assertEquals("KramerTech,zpoon", SelfUpdate.owners(owners));
        SelfUpdate.setOwners(owners, "zpoon");
        assertEquals("zpoon", SelfUpdate.owners(owners));
        // Emptied stays empty: the defaults don't come back.
        SelfUpdate.setOwners(owners, "");
        assertEquals("", SelfUpdate.owners(owners));
        try { SelfUpdate.setOwners(owners, "ok,bad name"); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("bad name")); }
        assertEquals("", SelfUpdate.owners(owners));
    }

    @Test public void theDescriptorIsItsNemesisSectionUnquoted() {
        Properties d = SelfUpdate.descriptor("[nemesis]\nid=tilepad\nshort-desc=\"Star = launcher\"\n; note=x\n[app.0]\nid=other\n");
        assertEquals("tilepad", d.getProperty("id"));
        assertEquals("Star = launcher", d.getProperty("short-desc"));
        assertNull(d.getProperty("; note"));
        assertEquals(2, d.size());
    }

    @Test public void anInstallBlocksAnotherOnlyWhileItCanStillBeRunning() throws Exception {
        assertFalse(SelfUpdate.installing(dir, "boot-a", 1000));
        SelfUpdate.started(dir, java.util.Arrays.asList(staged("tilepad", "1.2.0", "1.1.10")), "boot-a", 1000);
        assertTrue(SelfUpdate.installing(dir, "boot-a", 1000 + 60000));
        assertFalse(SelfUpdate.installing(dir, "boot-b", 1000 + 60000));
        assertFalse(SelfUpdate.installing(dir, "boot-a", 1000 + SelfUpdate.INSTALL_MS));
        append("item.tilepad.state=failed\nitem.tilepad.code=5\nstate=done\n");
        assertFalse(SelfUpdate.installing(dir, "boot-a", 1000 + 60000));
    }

    @Test public void theResultSaysHowEachPackageEnded() throws Exception {
        File apps = new File(dir, "apps");
        installed(apps, "tilepad", "1.1.10"); installed(apps, "dashcam", "1.2.7");
        assertTrue(SelfUpdate.result(dir, apps, "boot-a", 2000).isEmpty());
        SelfUpdate.started(dir, java.util.Arrays.asList(staged("dashcam", "1.2.8", "1.2.7"), staged("tilepad", "1.2.0", "1.1.10")), "boot-a", 1000);
        Properties r = SelfUpdate.result(dir, apps, "boot-a", 2000);
        assertEquals("installing", r.getProperty("update.state"));
        assertEquals("dashcam,tilepad", r.getProperty("update.items"));
        assertEquals("installing", r.getProperty("update.tilepad.state"));
        assertEquals("Rear Dashcam", r.getProperty("update.dashcam.name"));
        // Still unfinished long after it started this boot: the script died.
        assertEquals("interrupted", SelfUpdate.result(dir, apps, "boot-a", 1000 + SelfUpdate.INSTALL_MS).getProperty("update.tilepad.state"));
        // Restarted before the script wrote an outcome (a package's own postinstall can do that): the installed version says how far it got.
        installed(apps, "dashcam", "1.2.8");
        r = SelfUpdate.result(dir, apps, "boot-b", 2000);
        assertEquals("done", r.getProperty("update.state"));
        assertEquals("installed", r.getProperty("update.dashcam.state")); assertEquals("true", r.getProperty("update.dashcam.current"));
        assertEquals("interrupted", r.getProperty("update.tilepad.state")); assertEquals("false", r.getProperty("update.tilepad.current"));
        assertEquals("1.1.10", r.getProperty("update.tilepad.installed"));
        new File(new File(apps, "dashcam"), "NEMESIS.data").delete();
        assertEquals("", SelfUpdate.result(dir, apps, "boot-b", 2000).getProperty("update.dashcam.installed"));
        installed(apps, "dashcam", "1.2.8");

        append("item.dashcam.state=installed\nitem.dashcam.code=0\nitem.tilepad.state=failed\nitem.tilepad.code=1\nstate=done\n");
        Files.write(new File(dir, "tilepad.log"), ("installing tilepad\n\n2026-11-12 12:09:35 hook preinstall -> /data/local/tmp/nemesis/stage/tilepad/scripts/preinstall.sh\n"
            + "2026-11-12 12:09:35 preinstall failed rc=1\nUninstall HMG-API first, then reboot.\n").getBytes("UTF-8"));
        r = SelfUpdate.result(dir, apps, "boot-b", 2000);
        assertEquals("failed", r.getProperty("update.tilepad.state"));
        assertEquals("installing tilepad preinstall failed rc=1 Uninstall HMG-API first, then reboot.", r.getProperty("update.tilepad.detail"));
        assertEquals("1000", r.getProperty("update.started"));
        assertNull(r.getProperty("update.dashcam.detail"));
        new File(dir, "tilepad.log").delete();
        assertEquals("NEMESIS exited 1.", SelfUpdate.result(dir, apps, "boot-b", 2000).getProperty("update.tilepad.detail"));
    }

    @Test public void theScriptInstallsEachPackageThenRestartsIfAnyInstalled() throws Exception {
        assertEquals("done", runScript(0, 0));
        Properties r = Files.properties(new File(dir, SelfUpdate.RESULT));
        assertEquals("installed", r.getProperty("item.dashcam.state")); assertEquals("installed", r.getProperty("item.tilepad.state"));
        // Dashcam is new here, so it installs without --update.
        assertEquals("install " + new File(dir, "dashcam.zip").getPath() + "\ninstall --update " + new File(dir, SelfUpdate.ZIP).getPath()
            + "\nroot 1000 -c setprop sys.powerctl reboot\n", new String(Files.read(new File(dir, "calls")), "UTF-8"));
        assertFalse(new File(dir, SelfUpdate.ZIP).exists());
        assertFalse(new File(dir, SelfUpdate.SIGNATURE).exists());
        assertFalse(new File(dir, "dashcam.zip").exists());

        // One failing doesn't stop the next; the restart is still needed for the one that installed.
        new File(dir, "calls").delete();
        runScript(3, 0);
        r = Files.properties(new File(dir, SelfUpdate.RESULT));
        assertEquals("failed", r.getProperty("item.dashcam.state")); assertEquals("3", r.getProperty("item.dashcam.code"));
        assertEquals("installed", r.getProperty("item.tilepad.state"));
        assertTrue(new String(Files.read(new File(dir, "calls")), "UTF-8").endsWith("reboot\n"));
        assertEquals("nemesis says no", SelfUpdate.result(dir, new File(dir, "apps"), "boot-a", 2000).getProperty("update.dashcam.detail"));

        // Nothing installed: no restart, so the screen can say why.
        new File(dir, "calls").delete();
        runScript(3, 4);
        assertFalse(new String(Files.read(new File(dir, "calls")), "UTF-8").contains("reboot"));
    }

    /** SelfUpdate.script for dashcam then Tilepad, with /system/xbin/nemesis replaced by a fake that records its arguments. */
    private String runScript(int dashcam, int tilepad) throws Exception {
        java.util.List<SelfUpdate.Item> items = java.util.Arrays.asList(staged("dashcam", "1.2.8", null), staged("tilepad", "1.2.0", "1.1.10"));
        SelfUpdate.started(dir, items, "boot-a", 1000);
        Files.write(new File(dir, "dashcam.zip"), new byte[]{1});
        Files.write(new File(dir, SelfUpdate.ZIP), new byte[]{1}); Files.write(new File(dir, SelfUpdate.SIGNATURE), new byte[]{2});
        File fake = new File(dir, "nemesis");
        Files.write(fake, ("#!/bin/sh\necho \"$*\" >> " + dir + "/calls\n[ \"$1\" = install ] && echo 'nemesis says no'\n"
            + "case \"$*\" in *dashcam.zip) exit " + dashcam + ";; *tilepad.zip) exit " + tilepad + ";; esac\nexit 0\n").getBytes("UTF-8"));
        assertTrue(fake.setExecutable(true));
        File script = new File(dir, SelfUpdate.SCRIPT);
        String text = SelfUpdate.script(dir, items);
        assertTrue(text.contains("update_one dashcam\nupdate_one tilepad --update\n"));
        // The install screen starts first, from its own copy of Tilepad, and the installs don't wait on it.
        assertTrue(text.indexOf("com.tilepad.tweakcore.InstallScreen") < text.indexOf("update_one dashcam\n"));
        assertTrue(text.contains("cp /data/nemesis/apps/tilepad/app/Tilepad.apk \"$S/Tilepad.apk\""));
        assertTrue(text.contains("ANDROID_DATA=\"$S\""));
        Files.write(script, text.replace("/system/xbin/nemesis", fake.getPath()).replace("sleep 2", ":")
            .replace("export PATH=", "export PATH=" + System.getenv("PATH") + ":").getBytes("UTF-8"));
        Process p = new ProcessBuilder("sh", script.getPath()).redirectErrorStream(true).start();
        assertEquals(0, p.waitFor());
        return Files.properties(new File(dir, SelfUpdate.RESULT)).getProperty("state");
    }
    private static SelfUpdate.Item item(String id) { return new SelfUpdate.Item(id, null, null, null); }
    private static SelfUpdate.Item staged(String id, String version, String from) {
        SelfUpdate.Item i = item(id); i.version = version; i.from = from; i.name = id.equals("dashcam") ? "Rear Dashcam" : "Tilepad";
        return i;
    }
    private static String ids(java.util.List<SelfUpdate.Item> items) {
        StringBuilder s = new StringBuilder();
        for (SelfUpdate.Item i : items) s.append(s.length() == 0 ? "" : ",").append(i.id);
        return s.toString();
    }
    private static void installed(File apps, String id, String version) throws IOException {
        Files.write(new File(new File(apps, id), "NEMESIS.data"), ("[nemesis]\nid=" + id + "\npackage-version=" + version + "\n").getBytes("UTF-8"));
    }
    private static void linkRefused(File sources, File apps, File db, String id, String repo, String reason) {
        try { SelfUpdate.setSource(sources, apps, db, id, repo); fail("linked " + id); }
        catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains(reason)); }
    }
    private SelfUpdate.Item tilepad(File zip, File sig) { return new SelfUpdate.Item("tilepad", zip, sig.getPath(), null); }
    private void append(String lines) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, SelfUpdate.RESULT), true)) { out.write(lines.getBytes("UTF-8")); }
    }
    private void refused(SelfUpdate.Item item, String installed, String reason) throws Exception {
        try {
            SelfUpdate.stage(item, dir, installed, key.getPublic());
            fail("staged " + item.download);
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(reason));
        }
        String[] left = dir.list();
        assertEquals(java.util.Arrays.toString(left), 0, left.length);
    }
    private int made;
    private File pkg(String nemesis) throws IOException {
        File zip = new File(downloads, "package-" + made++ + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("NEMESIS.data")); z.write(("[nemesis]\nversion=1.1\n" + nemesis + "[service.0]\nname=longstar\n").getBytes("UTF-8"));
            z.putNextEntry(new ZipEntry("app/Tilepad.apk")); z.write(new byte[]{1, 2, 3});
            z.putNextEntry(new ZipEntry("scripts/post.sh")); z.write("#!/system/bin/sh\nlog \"done - reboot the head unit\"\nexec /system/bin/reboot\n".getBytes("UTF-8"));
        }
        return zip;
    }
    private File sign(File zip, PrivateKey with) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(with); s.update(Files.read(zip));
        File sig = new File(zip.getPath() + ".sig"); Files.write(sig, s.sign());
        return sig;
    }
}
