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
        final Mode mode;
        Release(String name, Usm usm, String automotive, String automotiveService, String framework, String framework2, String evSettings) {
            this(name, usm, usm == USM_V012 ? MODE_V012 : MODE_V015, automotive, automotiveService, framework, framework2, evSettings);
        }
        Release(String name, Usm usm, Mode mode, String automotive, String automotiveService, String framework, String framework2, String evSettings) {
            this.name = name; this.usm = usm; this.mode = mode; this.automotive = automotive; this.automotiveService = automotiveService;
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
    /**
     * IModeManager transaction codes for a steering-wheel key's assigned action, which the star hold
     * blocks during a hold: getKeyAction, disableKeyAction, enableKeyAction.
     */
    static final class Mode {
        final int keyAction, disableKeyAction, enableKeyAction;
        Mode(int keyAction, int disableKeyAction, int enableKeyAction) {
            this.keyAction = keyAction; this.disableKeyAction = disableKeyAction; this.enableKeyAction = enableKeyAction;
        }
    }
    // The same methods on every image; V012's codes are one lower. V014 added getSwrcCustomKeyAction
    // before them, so it has V015's codes.
    private static final Mode MODE_V015 = new Mode(0x2b, 0x2c, 0x2d), MODE_V012 = new Mode(0x2a, 0x2b, 0x2c);
    // setFrontSeatHeatDrvStep, setFrontSeatHeatAstStep, getFrontSeatHeat_DrvOption,
    // getFrontSeatHeat_AstOption, getSWRC_SteeringwheelHeatingOpt. V015 added a method before
    // each, so V012's and V014's codes are lower; the methods themselves are the same.
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
        new Release("Hyundai Canada V015.011.260206", USM_V015,
            "301b134d231b1ca1d0aa75ae090d219ac963fc935c3f3bc0b3b1393c6614703e",
            "efb949ec92fff388cbbb78e5bf4e16df3cc8d417dbf5cb5e87e62fb46d542549",
            "6748f43039561cb026986b19db0f81340877ad2206687eef2007d4e3f535f141",
            "75b442ad16bde67ff9a5fb76a27965287e946cd16ea9248344fa04fe90978095",
            "72d7fa3538bdd2aad81e52bc08f5372828a403e7cc8e4f469b2c42e763bf03e1"),
        new Release("Kia Canada V015.011.260206", USM_V015,
            "347d05034e6b25e38f8d0446a6e9f02f4c8f5b786c09e583a500235613514c18",
            "80bc04d04822464cfedb8878592f04eab8b16351be7aef6c70ff1ec9aa14d021",
            "24f15981db97bf388d13e130cbfc06b134fb57559cab75afeb5194131e5abac1",
            "3ff3271e0c7fbacdf49df582f82f5cd4d591f0bb5ae87341459092fbf2a39752",
            "2212c428240f4f8402e2efa60c21509f027ece10e0d99382441ff53cec14fbc5"),
        new Release("Hyundai USA V015.012.260507 DN8", USM_V015,
            "96f59ea5ad6b375db91dfc6f0ada04391892a6a05b225c34b8067b4d374e7e8f",
            "08e6601223fb52fa2dfc5a9acc07155b4ae9effcfa12caa63288ee2cd6b28b25",
            "82cfffe5864124d69e3220e34444b220b3c3a18dd3c486961155882711bdd060",
            "601dc8db4dca6806651c1074e26af7c831c63d159a8c3215f4e9910e2af6646f",
            "0500edcb2e081a4351b42aa5cc8909c6f73d00b52df9d24d139579839ebc0d44"),
        // KIA.USA image shared by the Niro EV (DE22EV) and Sorento (MQ422).
        new Release("Kia USA V015.012.260507 KIA", USM_V015,
            "4d0000635b140e43944e2b5c28fa7dfdba296914ec8df2807af2dcfd064c09cb",
            "ed3814199d50e6aa48b95fb10024e85a6646da789bbdac4afbe01ff657e8bcd5",
            "739c48b55bb101ddba8520671b46e783e675bf4091345bd82b67aa0f3675d2ce",
            "92930f5b08ada7fb0faf7722b8e5ddb8f53731615560a19c588ba57b1a694529",
            "cbd13937c20e284bbdf93dcc4be50224280d4a6edd1db8b6bf25097950eb3af7"),
        // ECOHMCSE: Hyundai's separate EV image for the IONIQ 6 (CEEV).
        new Release("Hyundai USA V015.012.260507 CE", USM_V015,
            "408b426e14b3f8dfdf1926e608e4f02689e89fc386b7f93a97f261ac5b6e4287",
            "afbc60509dd8a8fbac842523bf618424c088685d832a125a84d1d11dc1770bd4",
            "dac02b5ad1a5fc324aae228d6922c861baa82a18eefa59ee4d94b4d7bb7d7cc0",
            "8c02d6beba727b88a05489586b8183c4ecb4348404a0899cb4cb746a19c2e6b6",
            "f11270ef9bae3caa6d74bf49c330f7ec683b43c74da16048a9e385f42ad694fc"),
        new Release("Hyundai USA V016.010.260903 CE", USM_V015,
            "c2d3ae2375ed96f951f4eb4f29f15424d58779c18148a6ab08e491703ea96f2e",
            "c06c9dc292371a7614ff037755b8e62d9fe4afa05141f1f6688f9c37bc722664",
            "ae5df5a87d9d8c646d7eeb775d2c0838eb59b451259c35ccd4ed0d0313ef0405",
            "bd1cbeb3f29894d94f138c08fbabc3d3e74e17bb119baf7f78c95fe6c28d2973",
            "c06261db077a2ec003592195133847660ed9b005dd34280080653b803487c204"),
        // V014 has V012's heater codes but V015's key-action codes.
        new Release("Hyundai USA V014.010.250818", USM_V012, MODE_V015,
            "347bcc5d55944d3e11ba6554684a07d38e326bc4fd455efacd92ff4143def3d3",
            "d98111a84c2f3e7495133ce86ddd09687d6e54611db43fd88777201c9d1d64fb",
            "12de058f5c13d25f5211efa5b2cb43665c5881e78c1b215ed5d98ed69edd6543",
            "7562134f7d9f87a25de7f8068db49d0c0d253337e9eab68f8ed818dc055ba17d",
            "a031c36946706c0619012776b1822bc8dcd7dc336fd79001b07eb5e0fc5e1ce8"),
        // HYUNDAI.USA, Hyundai's image for its other models (the DN8 entry's at V015), here the Palisade's.
        new Release("Hyundai USA V016.010.260903 HYUNDAI", USM_V015,
            "1cc7060cbead8ca5ebbe72dad3570b9d27aa37e23803afe89b96d1f10a4611b6",
            "8b458b8fbc90ce79fd36fbde40e6d5e9638d754d18f935124e85c1bc628619f1",
            "31b669a42eca2f62a031c120f8cf0167e46ff105112026c66af5fc6694eef027",
            "901a56dfb81c0720bb05343822e95e6646e9f1361f41e4b6a08f47d4c3d649d1",
            "db7953205ac21af797eda3247e2f528b4c6e220a53eeedbd5883b97990744534"),
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
