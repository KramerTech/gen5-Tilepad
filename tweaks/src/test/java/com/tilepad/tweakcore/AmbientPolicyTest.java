package com.tilepad.tweakcore;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AmbientPolicyTest {
    @Test public void brandSelectionAndPaletteQuantizationSnapTo64EntryWheel() {
        assertEquals(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.brandForProduct("wp_daudioplus_neev23_us"));
        assertEquals(AmbientPolicy.Brand.KIA, AmbientPolicy.brandForProduct("wp_daudioplus_cvev_us"));
        assertEquals(64, AmbientPolicy.palette(AmbientPolicy.Brand.HYUNDAI).length);
        assertEquals(64, AmbientPolicy.palette(AmbientPolicy.Brand.KIA).length);
        // Exact wheel color returns index with 0 error.
        int exactHyundai = AmbientPolicy.colorAt(AmbientPolicy.Brand.HYUNDAI, 43);
        assertEquals(43, AmbientPolicy.nearestIndex(AmbientPolicy.Brand.HYUNDAI, exactHyundai));
        assertEquals(exactHyundai, AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, exactHyundai));
        // Arbitrary off-palette RGB snaps to an entry actually on the wheel.
        int snapped = AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, 0x00B4FF);
        int idx = AmbientPolicy.nearestIndex(AmbientPolicy.Brand.HYUNDAI, snapped);
        assertEquals(AmbientPolicy.colorAt(AmbientPolicy.Brand.HYUNDAI, idx), snapped);
        assertEquals(64, AmbientPolicy.groupedPalette(AmbientPolicy.Brand.HYUNDAI).length);
    }

    @Test public void brightnessAndRgbPacketsMatchVerifiedMicomProtocol() {
        assertEquals(1, AmbientPolicy.levelToRaw(0));
        assertEquals(2, AmbientPolicy.levelToRaw(1));
        assertEquals(11, AmbientPolicy.levelToRaw(10));
        assertEquals(11, AmbientPolicy.levelToRaw(99));
        assertEquals(0, AmbientPolicy.rawToLevel(1));
        assertEquals(1, AmbientPolicy.rawToLevel(2));
        assertEquals(10, AmbientPolicy.rawToLevel(11));

        byte[][] ch = AmbientPolicy.encodeRgb(0xFF7D73);
        assertArrayEquals(new byte[]{0, (byte) 0xff}, ch[0]);
        assertArrayEquals(new byte[]{0, (byte) 0x7d}, ch[1]);
        assertArrayEquals(new byte[]{0, (byte) 0x73}, ch[2]);
    }

    @Test public void parsesSmlStatusFromBytesAndLogcatLines() {
        byte[] raw = {0x00, (byte) 0xff, 0x00, (byte) 0x7d, 0x00, (byte) 0x73, 0x05, 0x02, 0x01};
        AmbientPolicy.SmlStatus s = AmbientPolicy.parseSmlStatusBytes(raw);
        assertNotNull(s);
        assertEquals(0xFF7D73, s.rgb());
        assertEquals(4, s.level());
        assertTrue(s.dimmedInDark());
        assertFalse(s.driveModeLinked());
        assertTrue(AmbientPolicy.validEquipment(s));

        AmbientPolicy.SmlStatus fromLog = AmbientPolicy.parseSmlStatusLine(
            "11-15 09:00:00.123 D/SystemService( 123): SYS_SML_STATUS_M_RESP: 0x00 0x00 0x00 0xd9 0x00 0xf5 0x08 0x02 0x01");
        assertNotNull(fromLog);
        assertEquals(0x00D9F5, fromLog.rgb());
        assertEquals(7, fromLog.level());
        assertNull(AmbientPolicy.parseSmlStatusBytes(new byte[8]));
        assertNull(AmbientPolicy.parseSmlStatusLine("unrelated log line"));
        assertFalse(AmbientPolicy.validEquipment(new AmbientPolicy.SmlStatus(0, 0, 0, 0, 0, 0)));
    }

    @Test public void dayNightClimateAndWeatherModesFollowRulesCleanly() {
        // Car theme without sunrise/sunset times: Day and Night follows carNight directly.
        AmbientPolicy.Target day = AmbientPolicy.dayNightTarget(
            AmbientPolicy.Brand.HYUNDAI, false, true, null, 0x00B4FF, 7, 0xFF7D73, 4);
        AmbientPolicy.Target night = AmbientPolicy.dayNightTarget(
            AmbientPolicy.Brand.HYUNDAI, true, true, null, 0x00B4FF, 7, 0xFF7D73, 4);
        assertTrue(day.active);
        assertTrue(night.active);
        assertEquals(7, day.level);
        assertEquals(4, night.level);
        assertNotEquals(day.rgb, night.rgb);

        // Sunrise/sunset fade computes 0 during day, 0.5 at sunset/sunrise, 1.0 at night, null if missing.
        int sunrise = AmbientPolicy.parseClockMinutes("06:30");
        int sunset = AmbientPolicy.parseClockMinutes("18:30");
        assertEquals(6 * 60 + 30, AmbientPolicy.parseClockMinutes("06:30:00"));
        assertEquals(18 * 60 + 30, AmbientPolicy.parseClockMinutes("6:30 PM"));
        assertEquals(6 * 60 + 30, AmbientPolicy.parseClockMinutes("20261116063000"));
        assertEquals(0.0, AmbientPolicy.sunsetFadeFraction(12 * 60, sunrise, sunset), 1e-6);
        assertEquals(0.5, AmbientPolicy.sunsetFadeFraction(sunset, sunrise, sunset), 1e-6);
        assertEquals(1.0, AmbientPolicy.sunsetFadeFraction(22 * 60, sunrise, sunset), 1e-6);
        assertNull(AmbientPolicy.sunsetFadeFraction(12 * 60, -1, sunset));

        // Live Stage-2 DATC dump: mTempUnit=2 (Fahrenheit), AmbientTempC=0xff (invalid -40), AmbientTempF=54.0.
        String liveDatcDump = "  mOpStatus: 1\n  mTempUnit: 2\n  mAmbientTempC: -40.0\n  mAmbientTempF: 54.0\n"
            + "  AmbientTempC : 0xff\n  AmbientTempF : 0x5e\n";
        assertEquals(Boolean.TRUE, AmbientPolicy.parseDatcFahrenheit(liveDatcDump));
        assertEquals(Boolean.FALSE, AmbientPolicy.parseDatcFahrenheit("  mTempUnit: 1\n"));
        assertEquals(12.222, HeaterPolicy.outsideC(liveDatcDump), 0.01);
        assertEquals(10.0, AmbientPolicy.weatherTempToCelsius(50.0, 2), 0.01);
        assertEquals(15.0, AmbientPolicy.weatherTempToCelsius(15.0, 1), 0.01);
        assertNull(AmbientPolicy.weatherTempToCelsius(-999.0, 1));

        // Climate mode classifies Off, Heating, Cooling, and Defrost without confusing MODE_AUTO=8.
        assertEquals(AmbientPolicy.ClimateState.OFF, AmbientPolicy.classifyClimate(0, 5, 5, 0, 0, 1, 1));
        assertEquals(AmbientPolicy.ClimateState.COOLING, AmbientPolicy.classifyClimate(1, 8, 8, 1, 0, 0, 1));
        assertEquals(AmbientPolicy.ClimateState.DEFROST, AmbientPolicy.classifyClimate(1, 8, 8, 0, 0, 0, 1));
        assertEquals(AmbientPolicy.ClimateState.DEFROST, AmbientPolicy.classifyClimate(1, 5, 1, 0, 0, 0, 0));
        assertEquals(AmbientPolicy.ClimateState.HEATING, AmbientPolicy.classifyClimate(1, 3, 3, 0, 0, 1, 0));
        AmbientPolicy.Target heatTarget = AmbientPolicy.climateTarget(
            AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.ClimateState.HEATING,
            0xFF7D73, 0x00B4FF, 0x00D9F5, 0x8E7BFF, 6);
        assertTrue(heatTarget.detail.contains("Heating"));

        // Fresh telematics weather overrides outside-temperature buckets; stale (>1h) falls back to Cold/Mild/Hot.
        AmbientPolicy.WeatherReport freshRain = new AmbientPolicy.WeatherReport(
            AmbientPolicy.WeatherCondition.RAIN, 22.0, "06:30", "18:30", 1_000_000L);
        assertTrue(AmbientPolicy.isWeatherFresh(freshRain, 1_500_000L));
        assertFalse(AmbientPolicy.isWeatherFresh(freshRain, 1_000_000L + AmbientPolicy.WEATHER_CACHE_FRESH_MS + 1));

        AmbientPolicy.Target rain = AmbientPolicy.weatherTarget(
            AmbientPolicy.Brand.HYUNDAI, freshRain, true, 22.0,
            50, 270, true, 0xFFCA52, 0x8FA8FF, 0x1E6FFF, 0x5BC0FF, 0x00B4FF, 0x38D5B4, 0xFF7D73, 6);
        assertEquals(AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, 0x1E6FFF), rain.rgb);

        AmbientPolicy.Target cold = AmbientPolicy.weatherTarget(
            AmbientPolicy.Brand.HYUNDAI, null, false, 3.0,
            50, 270, true, 0xFFCA52, 0x8FA8FF, 0x1E6FFF, 0x5BC0FF, 0x00B4FF, 0x38D5B4, 0xFF7D73, 6);
        AmbientPolicy.Target mild = AmbientPolicy.weatherTarget(
            AmbientPolicy.Brand.HYUNDAI, null, false, 18.0,
            50, 270, true, 0xFFCA52, 0x8FA8FF, 0x1E6FFF, 0x5BC0FF, 0x00B4FF, 0x38D5B4, 0xFF7D73, 6);
        AmbientPolicy.Target hot = AmbientPolicy.weatherTarget(
            AmbientPolicy.Brand.HYUNDAI, null, false, 31.0,
            50, 270, true, 0xFFCA52, 0x8FA8FF, 0x1E6FFF, 0x5BC0FF, 0x00B4FF, 0x38D5B4, 0xFF7D73, 6);
        assertEquals(AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, 0x00B4FF), cold.rgb);
        assertEquals(AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, 0x38D5B4), mild.rgb);
        assertEquals(AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, 0xFF7D73), hot.rgb);
        assertEquals("41 °F", AmbientPolicy.formatTemp(50, true));
        assertEquals("81 °F", AmbientPolicy.formatTemp(270, true));
    }

    public static final class FakePresentWeatherInfo {
        public int getIcon() { return 8; }
        public int getUnit() { return 2; }
        public double getValue() { return 54.0; }
        public double getRainfallAmount() { return 2.5; }
        public double getRainfallProbability() { return 80.0; }
        public double getSnowfallAmount() { return 0.0; }
        public String getSunriseTime() { return "06:45"; }
        public String getSunsetTime() { return "17:15"; }
    }

    public static final class FakeWeatherInfo {
        public String getCurrentLocTitle() { throw new AssertionError("privacy violation: currentLocTitle read"); }
        public String getDestLocTitle() { throw new AssertionError("privacy violation: destLocTitle read"); }
        public String getLocationCode() { throw new AssertionError("privacy violation: locationCode read"); }
        public List<FakePresentWeatherInfo> getmReadAndSavePresentWeatherInfoList() {
            return Collections.singletonList(new FakePresentWeatherInfo());
        }
    }

    public static final class FakeWeatherInfoList {
        public List<FakeWeatherInfo> getmReadAndSaveWeatherInfoList() {
            return Collections.singletonList(new FakeWeatherInfo());
        }
    }

    @Test public void weatherContainerExtractorReadsWeatherOnlyAndRespectsLocationPrivacy() {
        List<FakeWeatherInfoList> parcelList = Collections.singletonList(new FakeWeatherInfoList());
        AmbientPolicy.WeatherReport r = AmbientPolicy.parseWeatherContainer(parcelList, 1_234_567L);
        assertNotNull(r);
        assertEquals(AmbientPolicy.WeatherCondition.RAIN, r.condition);
        assertEquals(12.22, r.tempC, 0.05);
        assertEquals("06:45", r.sunriseHHMM);
        assertEquals("17:15", r.sunsetHHMM);
        assertEquals(1_234_567L, r.updatedAtMs);
        assertNull(AmbientPolicy.parseWeatherContainer(null, 1_234_567L));
    }

    @Test public void musicModeSupportsParkOnlyAndNonFlashingSlowCycleInDrive() {
        AmbientPolicy.MusicTracker tracker = new AmbientPolicy.MusicTracker();
        // Drive + Pause restores owner light and pauses.
        AmbientPolicy.Target drivingPause = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.SLOW_SPECTRUM,
            AmbientPolicy.MusicDriveBehavior.PAUSE, 0x00B4FF, 8, false, 0.9, new double[]{0.2, 0.9, 0.1}, 1000L);
        assertFalse(drivingPause.active);
        assertTrue(drivingPause.detail.contains("paused"));

        // Drive + Slow Color Cycle stays active and advances smoothly without strobing.
        AmbientPolicy.Target cycle1 = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.GENTLE_PULSE,
            AmbientPolicy.MusicDriveBehavior.SLOW_CYCLE, 0x00B4FF, 6, false, 0.0, new double[]{0, 0}, 1000L);
        AmbientPolicy.Target cycle2 = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.GENTLE_PULSE,
            AmbientPolicy.MusicDriveBehavior.SLOW_CYCLE, 0x00B4FF, 6, false, 0.0, new double[]{0, 0}, 2050L);
        assertTrue(cycle1.active && cycle2.active);
        assertEquals(6, cycle1.level);
        assertNotEquals(cycle1.rgb, cycle2.rgb);

        // Parked: brightness slew per tick is bounded by MUSIC_MAX_LEVEL_SLEW and color waits >= 1000 ms dwell.
        assertEquals(1000L, AmbientPolicy.MUSIC_COLOR_DWELL_MS);
        AmbientPolicy.Target p1 = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.SLOW_SPECTRUM,
            AmbientPolicy.MusicDriveBehavior.PAUSE, 0x00B4FF, 10, true, 1.0, new double[]{0.5, 0.4, 0.1, 0.05}, 3000L);
        assertTrue(p1.active);
        AmbientPolicy.Target p2 = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.SLOW_SPECTRUM,
            AmbientPolicy.MusicDriveBehavior.PAUSE, 0x00B4FF, 10, true, 1.0, new double[]{0.1, 0.95, 0.1, 0.05}, 3100L);
        assertTrue(p2.level - p1.level <= AmbientPolicy.MUSIC_MAX_LEVEL_SLEW);
        assertEquals(p1.rgb, p2.rgb);
        AmbientPolicy.Target p2b = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.SLOW_SPECTRUM,
            AmbientPolicy.MusicDriveBehavior.PAUSE, 0x00B4FF, 10, true, 1.0, new double[]{0.1, 0.95, 0.1, 0.05}, 3900L);
        assertEquals(p1.rgb, p2b.rgb);

        AmbientPolicy.Target p3 = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.SLOW_SPECTRUM,
            AmbientPolicy.MusicDriveBehavior.PAUSE, 0x00B4FF, 10, true, 1.0, new double[]{0.1, 0.95, 0.1, 0.05}, 4050L);
        assertNotEquals(p1.rgb, p3.rgb);
    }

    @Test public void visualizerFftAnalysisExtractsBandsAndRejectsSilence() {
        assertNull(AmbientPolicy.analyzeVisualizerFft(null, null));
        assertNull(AmbientPolicy.analyzeVisualizerFft(new byte[8], null));
        byte[] silentFft = new byte[256];
        byte[] flatWave = new byte[256];
        java.util.Arrays.fill(flatWave, (byte) 0x80);
        assertNull(AmbientPolicy.analyzeVisualizerFft(silentFft, flatWave));
        // DC-only component with zero AC bins and flat waveform still counts as silence.
        silentFft[0] = 100;
        assertNull(AmbientPolicy.analyzeVisualizerFft(silentFft, flatWave));

        // Bass-heavy FFT frame (bins k = 1, 2).
        byte[] bassFft = new byte[256];
        bassFft[2] = 90; bassFft[3] = 40;
        bassFft[4] = 80; bassFft[5] = -50;
        double[] bass = AmbientPolicy.analyzeVisualizerFft(bassFft, flatWave);
        assertNotNull(bass);
        assertEquals(5, bass.length);
        assertTrue(bass[0] > 0.05 && bass[0] <= 1.0);
        assertTrue(bass[1] > bass[4]);

        // Treble-heavy FFT frame (bins k = 90..120).
        byte[] trebleFft = new byte[256];
        for (int k = 90; k < 120; k++) {
            trebleFft[2 * k] = 60;
            trebleFft[2 * k + 1] = 60;
        }
        double[] treble = AmbientPolicy.analyzeVisualizerFft(trebleFft, flatWave);
        assertNotNull(treble);
        assertTrue(treble[4] > treble[1]);

        AmbientPolicy.MusicTracker tracker = new AmbientPolicy.MusicTracker();
        AmbientPolicy.Target warmTarget = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.SLOW_SPECTRUM,
            AmbientPolicy.MusicDriveBehavior.PAUSE, 0x00B4FF, 8, true,
            bass[0], new double[]{bass[1], bass[2], bass[3], bass[4]}, 1000L);
        assertEquals(AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, 0xFF4F6D), warmTarget.rgb);
        AmbientPolicy.Target coolTarget = tracker.update(AmbientPolicy.Brand.HYUNDAI, AmbientPolicy.MusicStyle.SLOW_SPECTRUM,
            AmbientPolicy.MusicDriveBehavior.PAUSE, 0x00B4FF, 8, true,
            treble[0], new double[]{treble[1], treble[2], treble[3], treble[4]}, 2100L);
        assertEquals(AmbientPolicy.nearestColor(AmbientPolicy.Brand.HYUNDAI, 0xAA4FFF), coolTarget.rgb);

        assertEquals(1, AmbientPolicy.MUSIC_MAX_LEVEL_SLEW);
        assertFalse(AmbientPolicy.isPhoneCallAudioMode(0));
        assertFalse(AmbientPolicy.isPhoneCallAudioMode(0, false));
        assertTrue(AmbientPolicy.isPhoneCallAudioMode(AmbientPolicy.AUDIO_MODE_RINGTONE));
        assertTrue(AmbientPolicy.isPhoneCallAudioMode(AmbientPolicy.AUDIO_MODE_IN_CALL));
        assertTrue(AmbientPolicy.isPhoneCallAudioMode(AmbientPolicy.AUDIO_MODE_IN_COMMUNICATION));
        assertTrue(AmbientPolicy.isPhoneCallAudioMode(0, true));
    }

    @Test public void rateLimiterTransactionRefusalAndExactOwnerRestoreAreDeterministic() {
        // Transaction verification with firmwareOwn=false must refuse mismatched transaction codes.
        assertEquals(0, Transactions.decide(76, 99, false));
        assertEquals(76, Transactions.decide(76, 76, false));

        AmbientPolicy.WriteTracker wt = new AmbientPolicy.WriteTracker();
        assertTrue(wt.canSend(1000L));
        wt.recordConfig(AmbientPolicy.SML_MODE_DRIVE_LINK, AmbientPolicy.SML_OFF, 1000L);
        wt.recordRgb(0x00B4FF, 1000L);
        wt.recordConfig(AmbientPolicy.SML_MODE_BRIGHTNESS, AmbientPolicy.levelToRaw(7), 1000L);
        assertFalse(wt.canSend(1050L));
        assertTrue(wt.canSend(1100L));

        AmbientPolicy.SmlStatus selfEcho = new AmbientPolicy.SmlStatus(0x00, 0xB4, 0xFF, 8, 2, 1);
        assertTrue(wt.isSelfEcho(selfEcho, 2500L));
        assertFalse(wt.isSelfEcho(selfEcho, 4500L));
        assertTrue(wt.isSelfEcho(selfEcho, 0x00B4FF, 7, 60_000L));
        AmbientPolicy.SmlStatus manualEdit = new AmbientPolicy.SmlStatus(0xFF, 0x00, 0x00, 8, 2, 1);
        assertFalse(wt.isSelfEcho(manualEdit, 1500L));
        assertFalse(wt.isSelfEcho(manualEdit, 0x00B4FF, 7, 60_000L));

        // Owner restore never writes SML_MODE_DIM_DARK (mode 8), restoring exact link + saved RGB + level.
        AmbientPolicy.SmlStatus customOwner = new AmbientPolicy.SmlStatus(0xFF, 0xAA, 0x00, 6, 2, 1);
        List<AmbientPolicy.SmlCommand> customRestore = AmbientPolicy.buildRestoreSteps(customOwner);
        assertEquals(3, customRestore.size());
        assertEquals(AmbientPolicy.SML_MODE_DRIVE_LINK, customRestore.get(0).mode);
        assertEquals(AmbientPolicy.SmlCommand.Kind.RGB, customRestore.get(1).kind);
        assertEquals(AmbientPolicy.SML_MODE_BRIGHTNESS, customRestore.get(2).mode);

        // Drive-mode linked owner (Stage 2 00 ff 00 58 00 58 0b 02 02): restores saved RGB, level, AND link ON.
        AmbientPolicy.SmlStatus linkedOwner = AmbientPolicy.parseSmlStatusLine(
            "D/SystemService(826): SYS_SML_STATUS_M_RESP: 0x00 0xff 0x00 0x58 0x00 0x58 0x0b 0x02 0x02");
        assertNotNull(linkedOwner);
        assertEquals(0xFF5858, linkedOwner.rgb());
        assertTrue(linkedOwner.driveModeLinked());
        List<AmbientPolicy.SmlCommand> linkedRestore = AmbientPolicy.buildRestoreSteps(linkedOwner);
        assertEquals(4, linkedRestore.size());
        assertEquals(AmbientPolicy.SML_OFF, linkedRestore.get(0).data);
        assertEquals(0xFF5858, linkedRestore.get(1).rgb);
        assertEquals(11, linkedRestore.get(2).data);
        assertEquals(AmbientPolicy.SML_MODE_DRIVE_LINK, linkedRestore.get(3).mode);
        assertEquals(AmbientPolicy.SML_ON, linkedRestore.get(3).data);

        Properties encoded = AmbientPolicy.encodeOwnerState(customOwner, true);
        AmbientPolicy.OwnerState decoded = AmbientPolicy.decodeOwnerState(encoded);
        assertNotNull(decoded);
        assertTrue(decoded.dirty);
        assertEquals(customOwner, decoded.status);
    }

    @Test public void configValidatesAllModesDefaultsAndThresholds() {
        Config c = new Config(new Properties());
        assertFalse(c.enabled("ambient"));
        assertEquals("daynight", c.get("ambientMode"));
        assertEquals("auto", c.get("ambientEquipment"));
        assertEquals("50", c.get("ambientWeatherColdC"));
        assertEquals("270", c.get("ambientWeatherHotC"));
        assertEquals("true", c.get("ambientSunsetFade"));
        assertEquals("pause", c.get("ambientMusicDrive"));

        c.set("ambient", "true");
        c.set("ambientMode", "climate");
        c.set("ambientClimateHeatColor", "ff7d73");
        assertEquals("#FF7D73", c.get("ambientClimateHeatColor"));
        c.set("ambientMode", "weather");
        c.set("ambientWeatherHotC", "300");
        for (Map.Entry<String, String> e : AmbientPolicy.defaultsForMode(AmbientPolicy.Mode.WEATHER).entrySet()) {
            c.set(e.getKey(), e.getValue());
        }
        assertEquals("270", c.get("ambientWeatherHotC"));
        assertTrue(c.needsVehicleCheck(new Config(new Properties())));
        for (String[] bad : new String[][]{
            {"ambientMode", "disco"}, {"ambientEquipment", "usb"}, {"ambientColor", "zzzzzz"},
            {"ambientLevel", "11"}, {"ambientMusicLevel", "0"}, {"ambientSunsetFade", "yes"},
            {"ambientMusicDrive", "strobe"}
        }) {
            try { c.set(bad[0], bad[1]); fail("Accepted " + bad[0] + "=" + bad[1]); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
