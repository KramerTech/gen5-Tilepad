package com.tilepad.tweakcore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddonCtlTest {
    /** A folder listing, files and root replies from maps; root commands are recorded. */
    static final class Fake extends AddonCtl {
        String[] listing;
        final List<String> files = new ArrayList<>();
        final Map<String, Reply> replies = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        @Override protected String[] list(String dir) { return listing == null ? null : listing.clone(); }
        @Override protected boolean exists(String path) { return files.contains(path); }
        @Override protected Reply root(String command) {
            commands.add(command);
            for (Map.Entry<String, Reply> e : replies.entrySet()) if (command.contains(e.getKey())) return e.getValue();
            return new Reply(0, "");
        }
        void has(String id, boolean page, boolean ctl) {
            if (page) files.add(APPS + "/" + id + "/" + AddonPage.FILE);
            if (ctl) files.add(APPS + "/" + id + "/bin/ctl.sh");
        }
    }

    @Test public void candidatesHaveAPageAndACtl() {
        Fake f = new Fake();
        f.listing = new String[]{"zeta", "example.page", "no.ctl", "no.page", "Bad Name", "tilepad"};
        f.has("zeta", true, true);
        f.has("example.page", true, true);
        f.has("no.ctl", true, false);
        f.has("no.page", false, true);
        f.has("Bad Name", true, true);
        f.has("tilepad", true, true);
        assertEquals(Arrays.asList("example.page", "tilepad", "zeta"), f.candidates());
        f.listing = null;
        assertTrue("no folder, no pages", f.candidates().isEmpty());
    }

    @Test public void loadsAsRootInsideTheCheckedFolderAndSkipsWhatItsTold() {
        Fake f = new Fake();
        f.listing = new String[]{"example.page", "tilepad"};
        f.has("example.page", true, true);
        f.has("tilepad", true, true);
        f.replies.put("cat ./" + AddonPage.FILE, new AddonCtl.Reply(0, AddonPageTest.EXAMPLE));
        List<AddonCtl.Loaded> loaded = f.load(Arrays.asList("tilepad"));
        assertEquals(1, loaded.size());
        assertEquals("Example Package", loaded.get(0).page.title);
        assertEquals(1, f.commands.size());
        assertTrue(f.commands.get(0).startsWith("(cd '/data/nemesis/apps/example.page' || exit 3; [ \"$(pwd -P)\" = '/data/nemesis/apps/example.page' ] || { echo error=untrusted; exit 3; }; for f in . bin bin/ctl.sh tilepad.properties; do"));
        assertTrue(f.commands.get(0).endsWith("done; cat ./tilepad.properties)"));
    }

    @Test public void anUntrustedOrUnreadablePageIsNotUsed() {
        Fake f = new Fake();
        f.replies.put("cat", new AddonCtl.Reply(3, "error=untrusted\n"));
        AddonCtl.Loaded l = f.load("example.page");
        assertNull(l.page);
        assertEquals("its files aren't the system's alone, so Tilepad doesn't use them", l.problem);
        f.replies.put("cat", new AddonCtl.Reply(1, ""));
        assertEquals("its page file couldn't be read", f.load("example.page").problem);
        f.replies.put("cat", new AddonCtl.Reply(0, "schema=9\ntitle=T\n"));
        assertEquals("its page file is for another version of Tilepad", f.load("example.page").problem);
        assertNull("a bad folder name is never sent", f.load("a';reboot;'").page);
        assertEquals(3, f.commands.size());
    }

    @Test public void setsOnlyThePagesOwnSettingsAndValues() throws Exception {
        Fake f = new Fake();
        AddonPage p = AddonPage.parse("example.page", AddonPageTest.EXAMPLE);
        assertTrue(f.set(p, "persist.eg.exa_mode", "busy").ok());
        assertTrue(f.commands.get(0).startsWith("(cd '/data/nemesis/apps/example.page' || exit 3; [ \"$(pwd -P)\" = '/data/nemesis/apps/example.page' ] || { echo error=untrusted; exit 3; }; for f in . bin bin/ctl.sh; do"));
        assertTrue(f.commands.get(0).endsWith("done; sh ./bin/ctl.sh set 'exa_mode' 'busy')"));
        assertEquals("unknown_name", f.set(p, "persist.eg.other", "1").error());
        assertEquals("bad_value", f.set(p, "persist.eg.exa_vol", "1000").error());
        assertEquals("bad_value", f.set(p, "persist.eg.exa_vol", "50'; reboot; '").error());
        assertEquals("nothing sent for a refused one", 1, f.commands.size());
        assertEquals("This package's files aren't the system's alone, so Tilepad didn't run its settings command.",
            AddonCtl.setProblem(new AddonCtl.Reply(3, "error=untrusted")));
        assertNull(AddonCtl.setProblem(new AddonCtl.Reply(0, "")));
    }

    @Test public void requestsAreThePagesOwnAndTheirResultsAreMatchedBySeq() throws Exception {
        Fake f = new Fake();
        AddonPage p = AddonPage.parse("example.colors", AddonPageTest.COLORS);
        f.replies.put("req 'restore'", new AddonCtl.Reply(0, "seq=42\n"));
        assertEquals(42, f.req(p, "restore"));
        assertTrue(f.commands.get(0).endsWith("done; sh ./bin/ctl.sh req 'restore')"));
        assertEquals("not one of its actions", -1, f.req(p, "reboot"));
        assertEquals(1, f.commands.size());
        assertNull(AddonCtl.result(null, "restore", 42));
        assertNull("an earlier request's", AddonCtl.result("restore:41:restored", "restore", 42));
        assertEquals("restored", AddonCtl.result("restore:42:restored", "restore", 42));
        assertEquals("a later one's", "", AddonCtl.result("restore:43:restored", "restore", 42));
        assertNull(AddonCtl.result("junk", "restore", 42));
    }

    @Test public void whatAnActionSaysAfterItsRequest() throws Exception {
        AddonPage.Control c = AddonPage.parse("example.colors", "schema=1\ntitle=Example Colors\nresults=eg.res.exa\n"
            + "control.1.type=action\ncontrol.1.label=Restore\ncontrol.1.request=restore\n"
            + "control.1.result.restored=Your setting is back.\n").action("restore");
        assertEquals("the page's own words", "Your setting is back.", AddonCtl.said(c, 4, "restored"));
        assertEquals("a word the page doesn't have", "The package answered: not fitted.", AddonCtl.said(c, 4, "not_fitted"));
        assertTrue("not sent", AddonCtl.said(c, -1, null).contains("didn't run"));
        assertTrue("no answer in time", AddonCtl.said(c, 4, null).contains("didn't answer in time"));
        assertTrue("a later request answered", AddonCtl.said(c, 4, "").contains("Another request"));
    }

    // The folder check, run in real shells: sh, and mksh (the car's shell) when it's installed. A
    // stand-in ls says who owns each name and its mode, as the car's toolbox ls -ld prints them.

    @Test public void theCheckRunsOnlyForTheSystemsOwnFolder() throws Exception {
        for (String shell : shells()) {
            assertEquals(shell, "0:ran", check(shell, null));
            assertEquals(shell, "3:error=untrusted", check(shell, "bin/ctl.sh=-rw-r--r-- u0_a99"));
            assertEquals(shell, "3:error=untrusted", check(shell, ".=drwxr-xr-x u0_a99"));
            assertEquals("group writable", "3:error=untrusted", check(shell, "bin=drwxrwxr-x root"));
            assertEquals("writable by others", "3:error=untrusted", check(shell, "tilepad.properties=-rw-r--rw- root"));
            assertEquals("a link", "3:error=untrusted", check(shell, "bin/ctl.sh=lrwxrwxrwx root"));
            assertEquals("not a folder or plain file", "3:error=untrusted", check(shell, "tilepad.properties=prw-r--r-- root"));
            assertEquals("the folder a link to another", "3:error=untrusted", check(shell, null, true));
            assertEquals("missing", "3:error=untrusted", check(shell, "tilepad.properties=MISSING"));
        }
    }

    /** Runs the check for a folder whose ls says root/755 (644 for files), with override changed; "<status>:<output>". */
    private static String check(String shell, String override) throws Exception { return check(shell, override, false); }

    /** As check, through a link to the package's folder when viaLink. */
    private static String check(String shell, String override, boolean viaLink) throws Exception {
        // its real path, so the folder's own check (pwd -P) compares like with like
        File tmp = java.nio.file.Files.createTempDirectory("addon").toFile().getCanonicalFile();
        File pkg = new File(tmp, "pkg"), bin = new File(pkg, "bin"), stub = new File(tmp, "stub");
        assertTrue(bin.mkdirs() && stub.mkdirs());
        write(new File(bin, "ctl.sh"), "echo ctl\n");
        write(new File(pkg, "tilepad.properties"), "schema=1\n");
        StringBuilder table = new StringBuilder(".=drwxr-xr-x root\nbin=drwxr-xr-x root\nbin/ctl.sh=-rw-r--r-- root\ntilepad.properties=-rw-r--r-- root\n");
        if (override != null) table.insert(0, override + "\n");
        write(new File(tmp, "table"), table.toString());
        File ls = new File(stub, "ls");
        write(ls, "#!/bin/sh\n# ls -ld NAME, from the table: first line for NAME wins\n"
            + "line=$(grep -m1 \"^$2=\" '" + tmp + "/table' | cut -d= -f2-)\n"
            + "[ -z \"$line\" ] || [ \"$line\" = MISSING ] && exit 1\n"
            + "set -- $line\necho \"$1 $2     root              2000-01-01 00:00 x\"\n");
        assertTrue(ls.setExecutable(true));
        File dir = pkg;
        if (viaLink) {
            dir = new File(tmp, "other");
            java.nio.file.Files.createSymbolicLink(dir.toPath(), pkg.toPath());
        }
        String command = PackageFolder.run(dir.getPath(), new String[]{"bin", "bin/ctl.sh", "tilepad.properties"}, "echo ran");
        ProcessBuilder pb = new ProcessBuilder(shell, "-c", command).redirectErrorStream(true);
        pb.environment().put("PATH", stub.getPath() + ":/usr/bin:/bin");
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[1024];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
        return p.waitFor() + ":" + out.toString("UTF-8").trim();
    }

    private static List<String> shells() {
        List<String> out = new ArrayList<>(Arrays.asList("/bin/sh"));
        if (new File("/usr/bin/mksh").canExecute()) out.add("/usr/bin/mksh");
        return out;
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }
}
