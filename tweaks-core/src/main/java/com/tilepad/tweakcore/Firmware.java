package com.tilepad.tweakcore;

/**
 * Firmware releases whose files Tilepad has checked. One /system image serves every model of a
 * release, so these are per release, not per car. A release is added from a compatibility log
 * once the interfaces Tilepad calls are confirmed unchanged (docs/TWEAKS.md). No I/O here.
 */
final class Firmware {
    static final class Release {
        final String name, automotive, automotiveService, framework, framework2, evSettings;
        Release(String name, String automotive, String automotiveService, String framework, String framework2, String evSettings) {
            this.name = name; this.automotive = automotive; this.automotiveService = automotiveService;
            this.framework = framework; this.framework2 = framework2; this.evSettings = evSettings;
        }
    }
    static final Release[] SUPPORTED = {
        new Release("USA V015.012.260507",
            "dd72e04bce0a2b4bda1506308ac21f91002c1436553b8156c345cc4313661338",
            "3cf40fb00854c63afa49abea1dad18a0afcbc0dd68b17de9ed7ea3f5aeab4222",
            "ebff68c02c0d82dbd22fd8ec7cc6f71d96ef653f8d8843c2f1748e80b230d0c8",
            "00d62f627875e9bc2ada061761c70e2dda7ef15a0c1913c085563474d2ffa3e6",
            "106158a5281616aaeafc4bf84a0f043c71ed72f570d1a47f036cb188f99ea925"),
        new Release("USA V016.010.260903",
            "c52e0825956880d627b32979022705a618200f059476c094d729133d1ff6a3be",
            "bf17e682d1beb349ae8ef2acaae6d6e908fe42e88853d7145ee16b29bb4d9ec1",
            "c8bca2858f208ac5a803f4e66c50429011fe479014e3697992ddd777a0961732",
            "1e656118846bf6938f37d6b33168d21a219dbe610e7fc616a24f7773a3191e43",
            "c1c4f113607990ffb300e280b0cd4ef92137983037d3f4f27d86098dbc49908c"),
    };
    /** The vehicle interfaces (automotive.odex and automotive-service.odex) of a supported release. */
    static boolean vehicle(String automotive, String automotiveService) {
        for (Release r : SUPPORTED) if (r.automotive.equals(automotive) && r.automotiveService.equals(automotiveService)) return true;
        return false;
    }
    /** The projection interfaces (framework.odex and framework2.odex) of a supported release. */
    static boolean projection(String framework, String framework2) {
        for (Release r : SUPPORTED) if (r.framework.equals(framework) && r.framework2.equals(framework2)) return true;
        return false;
    }
    /** The EV Settings app (HKMC_TAppEV3.apk) of a supported release. */
    static boolean evSettings(String apk) {
        for (Release r : SUPPORTED) if (r.evSettings.equals(apk)) return true;
        return false;
    }
    private Firmware() { }
}
