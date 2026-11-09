package com.tilepad.tweakcore;

/**
 * Firmware images whose files Tilepad has checked. One /system image serves several models (the
 * unit sets its model at boot), and Hyundai and Kia each build their own image of a release, so
 * these are per image, not per car. An image is added from a compatibility log once the
 * interfaces Tilepad calls are confirmed unchanged, or their differences are recorded here, as
 * V012's heater codes are (docs/TWEAKS.md). No I/O here.
 */
final class Firmware {
    static final class Release {
        final String name, automotive, automotiveService, framework, framework2, evSettings;
        final Usm usm;
        Release(String name, Usm usm, String automotive, String automotiveService, String framework, String framework2, String evSettings) {
            this.name = name; this.usm = usm; this.automotive = automotive; this.automotiveService = automotiveService;
            this.framework = framework; this.framework2 = framework2; this.evSettings = evSettings;
        }
    }
    /** IUSMService transaction codes for the heaters: the seat heat requests and the equipment getters. */
    static final class Usm {
        final int driverHeat, passengerHeat, driverFitted, passengerFitted, wheelFitted;
        Usm(int driverHeat, int passengerHeat, int driverFitted, int passengerFitted, int wheelFitted) {
            this.driverHeat = driverHeat; this.passengerHeat = passengerHeat;
            this.driverFitted = driverFitted; this.passengerFitted = passengerFitted; this.wheelFitted = wheelFitted;
        }
    }
    // setFrontSeatHeatDrvStep, setFrontSeatHeatAstStep, getFrontSeatHeat_DrvOption,
    // getFrontSeatHeat_AstOption, getSWRC_SteeringwheelHeatingOpt. V015 added a method before
    // each, so V012's codes are lower; the methods themselves are the same.
    private static final Usm USM_V015 = new Usm(0xd5, 0xd6, 0x245, 0x246, 0x241), USM_V012 = new Usm(0xd4, 0xd5, 0x243, 0x244, 0x23f);
    static final Release[] SUPPORTED = {
        new Release("Hyundai USA V015.012.260507", USM_V015,
            "dd72e04bce0a2b4bda1506308ac21f91002c1436553b8156c345cc4313661338",
            "3cf40fb00854c63afa49abea1dad18a0afcbc0dd68b17de9ed7ea3f5aeab4222",
            "ebff68c02c0d82dbd22fd8ec7cc6f71d96ef653f8d8843c2f1748e80b230d0c8",
            "00d62f627875e9bc2ada061761c70e2dda7ef15a0c1913c085563474d2ffa3e6",
            "106158a5281616aaeafc4bf84a0f043c71ed72f570d1a47f036cb188f99ea925"),
        new Release("Hyundai USA V016.010.260903", USM_V015,
            "c52e0825956880d627b32979022705a618200f059476c094d729133d1ff6a3be",
            "bf17e682d1beb349ae8ef2acaae6d6e908fe42e88853d7145ee16b29bb4d9ec1",
            "c8bca2858f208ac5a803f4e66c50429011fe479014e3697992ddd777a0961732",
            "1e656118846bf6938f37d6b33168d21a219dbe610e7fc616a24f7773a3191e43",
            "c1c4f113607990ffb300e280b0cd4ef92137983037d3f4f27d86098dbc49908c"),
        new Release("Kia USA V015.012.260507", USM_V015,
            "b9e7e02b81e1a8cfcf767c6b8bbb28bcf1d2c411a208fbf6a465e7f0c662774a",
            "8af745b41aa28f490953d61531215cd94e059f104b31f6236a8f6c7af23b8062",
            "903b239f20a93f7bfaa2ac14d270c4778c9a7dc95aad33a586737d1bf56a51d9",
            "fc2834cd2473b36b491408ed5ecf76d9cc20d13856474941f98b557e4946b3a3",
            "2212c428240f4f8402e2efa60c21509f027ece10e0d99382441ff53cec14fbc5"),
        new Release("Hyundai USA V012.010.240822", USM_V012,
            "0f6786b9c8c167f09c08c559afea7da1e2137e2a1968b9ab372def65762674f9",
            "2758a413c184855bf92080cfdc6decbfcd6f09775128a982b1eee211993772a1",
            "ffc906e223bbb1e2b28103ee561adc0b9d70bd519f093c26d56b57af021e24a8",
            "1db8bbad2f805fc7b8ca7134a7bda72bd2e33a33a5b9950742413555b0f8007c",
            "9a7c60551ae84417e3542b867cf92b797a3466505c84edbc7a005493fe7d4301"),
    };
    /** The supported release with these vehicle interfaces (automotive.odex and automotive-service.odex), or null. */
    static Release vehicleRelease(String automotive, String automotiveService) {
        for (Release r : SUPPORTED) if (r.automotive.equals(automotive) && r.automotiveService.equals(automotiveService)) return r;
        return null;
    }
    static boolean vehicle(String automotive, String automotiveService) { return vehicleRelease(automotive, automotiveService) != null; }
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
