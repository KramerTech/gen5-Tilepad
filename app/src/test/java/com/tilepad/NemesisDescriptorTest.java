package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

public class NemesisDescriptorTest {
    @Test public void readsTheFaviconAndTheApkPackages() {
        // EQ Engine 0.7.9's descriptor, cut down.
        NemesisDescriptor d = NemesisDescriptor.parse("[nemesis]\nversion=1.2\nid=eqengine\n"
            + "favicon=favicon.png\nshort-desc=\"EQ for Android Auto\"\n\n"
            + "[app.0]\nkind=apk\npath=app/EQEngine.apk\npackage=com.eqengine.app\n"
            + "component=com.eqengine.app/com.eqengine.app.MainActivity\n\n"
            + "[service.0]\nname=boot\nkind=sh\npath=bin/boot.sh\n");
        assertEquals("favicon.png", d.favicon);
        assertEquals(Collections.singletonList("com.eqengine.app"), d.packages);
    }

    @Test public void takesThePackageFromTheComponentAndSkipsScriptLaunchers() {
        NemesisDescriptor d = NemesisDescriptor.parse("[nemesis]\r\nfavicon=\"res/icon.png\"\r\n"
            + "[app.0]\r\nkind=apk\r\ncomponent=com.a/.Main\r\n"
            + "[app.1]\r\nkind=sh\r\npath=bin/open.sh\r\npackage=com.b\r\n"
            + "[app.2]\r\npackage=com.c\r\n");
        assertEquals("res/icon.png", d.favicon);
        assertEquals(Arrays.asList("com.a", "com.c"), d.packages);
    }

    @Test public void refusesAFaviconOutsideThePackage() {
        assertNull(NemesisDescriptor.parse("[nemesis]\nfavicon=../x.png\n").favicon);
        assertNull(NemesisDescriptor.parse("[nemesis]\nfavicon=/data/x.png\n").favicon);
        assertNull(NemesisDescriptor.parse("[nemesis]\nfavicon=\n").favicon);
        // A favicon key outside [nemesis] is not the package's.
        assertNull(NemesisDescriptor.parse("[app.0]\nfavicon=a.png\npackage=com.a\n").favicon);
    }
}
