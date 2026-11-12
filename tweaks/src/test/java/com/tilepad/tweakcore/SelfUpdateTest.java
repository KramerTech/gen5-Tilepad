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
        assertEquals("1.2.0", SelfUpdate.stage(zip, sign(zip, key.getPrivate()), "1.1.10", dir, key.getPublic()));
        assertArrayEquals(Files.read(zip), Files.read(new File(dir, SelfUpdate.ZIP)));
        assertTrue(new File(dir, SelfUpdate.SIGNATURE).isFile());
    }

    @Test public void aDownloadWithoutTheReleaseSignatureIsRefusedAndRemoved() throws Exception {
        File zip = pkg("id=tilepad\npackage-version=1.2.0\n"), other = pkg("id=tilepad\npackage-version=1.2.1\n");
        refused(zip, sign(other, key.getPrivate()), "1.1.10", "isn't signed with");
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        refused(zip, sign(zip, g.generateKeyPair().getPrivate()), "1.1.10", "isn't signed with");
        File garbage = new File(downloads, "garbage.sig"); Files.write(garbage, new byte[256]);
        refused(zip, garbage, "1.1.10", "isn't signed with");
    }

    @Test public void onlyANewerTilepadPackageIsStaged() throws Exception {
        File same = pkg("id=tilepad\npackage-version=1.1.10\n");
        refused(same, sign(same, key.getPrivate()), "1.1.10", "already installed");
        File older = pkg("id=tilepad\npackage-version=1.1.9\n");
        refused(older, sign(older, key.getPrivate()), "1.1.10", "already installed");
        File other = pkg("id=adb.wifi\npackage-version=9.0\n");
        refused(other, sign(other, key.getPrivate()), "1.1.10", "isn't a Tilepad package");
        File none = new File(downloads, "none.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(none))) { z.putNextEntry(new ZipEntry("README")); z.write(1); }
        refused(none, sign(none, key.getPrivate()), "1.1.10", "isn't a Tilepad package");
        refused(new File(downloads, "missing.zip"), new File(downloads, "missing.sig"), "1.1.10", "missing");
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
        SelfUpdate.started(dir, "1.2.0", "1.1.10", "boot-a", 1000);
        assertTrue(SelfUpdate.installing(dir, "boot-a", 1000 + 60000));
        assertFalse(SelfUpdate.installing(dir, "boot-b", 1000 + 60000));
        assertFalse(SelfUpdate.installing(dir, "boot-a", 1000 + SelfUpdate.INSTALL_MS));
        append("state=failed\ncode=5\n");
        assertFalse(SelfUpdate.installing(dir, "boot-a", 1000 + 60000));
    }

    @Test public void theResultSaysHowTheLastUpdateEnded() throws Exception {
        assertTrue(SelfUpdate.result(dir, "boot-a", "1.1.10", 2000).isEmpty());
        SelfUpdate.started(dir, "1.2.0", "1.1.10", "boot-a", 1000);
        assertEquals("installing", SelfUpdate.result(dir, "boot-a", "1.1.10", 2000).getProperty("update.state"));
        // Still unfinished long after it started this boot: the script died.
        assertEquals("interrupted", SelfUpdate.result(dir, "boot-a", "1.1.10", 1000 + SelfUpdate.INSTALL_MS).getProperty("update.state"));
        // Restarted before the script wrote its outcome: the installed version says how far it got.
        assertEquals("interrupted", SelfUpdate.result(dir, "boot-b", "1.1.10", 2000).getProperty("update.state"));
        assertEquals("installed", SelfUpdate.result(dir, "boot-b", "1.2.0", 2000).getProperty("update.state"));
        append("state=installed\ncode=0\n");
        Properties r = SelfUpdate.result(dir, "boot-b", "1.2.0", 2000);
        assertEquals("installed", r.getProperty("update.state"));
        assertEquals("1.2.0", r.getProperty("update.version"));
        assertEquals("1000", r.getProperty("update.started"));
        assertNull(r.getProperty("update.detail"));

        SelfUpdate.started(dir, "1.2.0", "1.1.10", "boot-c", 2000);
        append("state=failed\ncode=1\n");
        Files.write(new File(dir, SelfUpdate.LOG), "installing tilepad\n\npreinstall failed rc=1\nUninstall HMG-API first, then reboot.\n".getBytes("UTF-8"));
        r = SelfUpdate.result(dir, "boot-c", "1.1.10", 2000);
        assertEquals("failed", r.getProperty("update.state"));
        assertEquals("preinstall failed rc=1 Uninstall HMG-API first, then reboot.", r.getProperty("update.detail"));
        new File(dir, SelfUpdate.LOG).delete();
        assertEquals("NEMESIS exited 1.", SelfUpdate.result(dir, "boot-c", "1.1.10", 2000).getProperty("update.detail"));
    }

    @Test public void theScriptRestartsOnlyAfterNemesisInstalledTheUpdate() throws Exception {
        assertEquals("installed", runScript(0));
        assertEquals("install --update " + new File(dir, SelfUpdate.ZIP).getPath() + "\nroot 1000 -c setprop sys.powerctl reboot\n",
            new String(Files.read(new File(dir, "calls")), "UTF-8"));
        assertFalse(new File(dir, SelfUpdate.ZIP).exists());
        assertFalse(new File(dir, SelfUpdate.SIGNATURE).exists());
        assertEquals("installed", SelfUpdate.result(dir, "boot-a", "1.1.10", 2000).getProperty("update.state"));

        new File(dir, "calls").delete();
        assertEquals("failed", runScript(3));
        assertEquals("install --update " + new File(dir, SelfUpdate.ZIP).getPath() + "\n", new String(Files.read(new File(dir, "calls")), "UTF-8"));
        Properties r = SelfUpdate.result(dir, "boot-a", "1.1.10", 2000);
        assertEquals("failed", r.getProperty("update.state"));
        assertEquals("nemesis says no", r.getProperty("update.detail"));
    }

    /** SelfUpdate.script with /system/xbin/nemesis replaced by a fake that records its arguments. */
    private String runScript(int code) throws Exception {
        SelfUpdate.started(dir, "1.2.0", "1.1.10", "boot-a", 1000);
        Files.write(new File(dir, SelfUpdate.ZIP), new byte[]{1}); Files.write(new File(dir, SelfUpdate.SIGNATURE), new byte[]{2});
        File fake = new File(dir, "nemesis");
        Files.write(fake, ("#!/bin/sh\necho \"$*\" >> " + dir + "/calls\n[ \"$1\" = install ] && echo 'nemesis says no'\n"
            + "[ \"$1\" = install ] && exit " + code + "\nexit 0\n").getBytes("UTF-8"));
        assertTrue(fake.setExecutable(true));
        File script = new File(dir, SelfUpdate.SCRIPT);
        String text = SelfUpdate.script(dir);
        assertTrue(text.contains("/system/xbin/nemesis install --update"));
        Files.write(script, text.replace("/system/xbin/nemesis", fake.getPath()).replace("sleep 2", ":")
            .replace("export PATH=", "export PATH=" + System.getenv("PATH") + ":").getBytes("UTF-8"));
        Process p = new ProcessBuilder("sh", script.getPath()).redirectErrorStream(true).start();
        assertEquals(0, p.waitFor());
        return Files.properties(new File(dir, SelfUpdate.RESULT)).getProperty("state");
    }
    private void append(String lines) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, SelfUpdate.RESULT), true)) { out.write(lines.getBytes("UTF-8")); }
    }
    private void refused(File zip, File sig, String installed, String reason) throws Exception {
        try {
            SelfUpdate.stage(zip, sig, installed, dir, key.getPublic());
            fail("staged " + zip);
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(reason));
        }
        assertFalse(new File(dir, SelfUpdate.ZIP).exists());
        assertFalse(new File(dir, SelfUpdate.SIGNATURE).exists());
    }
    private int made;
    private File pkg(String nemesis) throws IOException {
        File zip = new File(downloads, "tilepad-" + made++ + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("NEMESIS.data")); z.write(("[nemesis]\nversion=1.1\n" + nemesis + "[service.0]\nname=longstar\n").getBytes("UTF-8"));
            z.putNextEntry(new ZipEntry("app/Tilepad.apk")); z.write(new byte[]{1, 2, 3});
        }
        return zip;
    }
    private File sign(File zip, PrivateKey with) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(with); s.update(Files.read(zip));
        File sig = new File(zip.getPath() + ".sig"); Files.write(sig, s.sign());
        return sig;
    }
}
