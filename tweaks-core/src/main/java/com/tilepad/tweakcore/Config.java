package com.tilepad.tweakcore;

import java.util.Properties;

/** Closed schema: configuration is data, never shell code. Temperature is stored in tenths C. */
public final class Config {
    public static final String[] IDS = {"dst", "consent", "heaters", "tracks", "nav", "popover", "sync", "driveronly", "projection", "chargeaway", "ambient", "ota"};
    public final Properties values;
    public Config(Properties values) { this.values = values; }
    public boolean enabled(String id) { return "true".equals(values.getProperty(id, "false")); }
    public String get(String key) {
        switch (key) {
            case "region": return values.getProperty(key, "us");
            case "threshold": return values.getProperty(key, "100");
            case "driver": case "wheel": return values.getProperty(key, "true");
            case "passenger": return values.getProperty(key, "false");
            case "driverLevel": case "passengerLevel": return values.getProperty(key, "3");
            case "units": return values.getProperty(key, "F");
            case "homeLat": case "homeLon": return values.getProperty(key, "");
            case "ambientMode": return values.getProperty(key, "daynight");
            case "ambientEquipment": return values.getProperty(key, "auto");
            case "ambientColor": case "ambientDayColor":
            case "ambientClimateCoolColor": case "ambientWeatherColdColor": return values.getProperty(key, "#00B4FF");
            case "ambientNightColor": case "ambientClimateHeatColor":
            case "ambientWeatherHotColor": return values.getProperty(key, "#FF7D73");
            case "ambientClimateDefrostColor": return values.getProperty(key, "#00D9F5");
            case "ambientClimateOffColor": return values.getProperty(key, "#8E7BFF");
            case "ambientWeatherClearColor": return values.getProperty(key, "#FFCA52");
            case "ambientWeatherCloudyColor": return values.getProperty(key, "#8FA8FF");
            case "ambientWeatherRainColor": return values.getProperty(key, "#1E6FFF");
            case "ambientWeatherSnowColor": return values.getProperty(key, "#5BC0FF");
            case "ambientWeatherMildColor": return values.getProperty(key, "#38D5B4");
            case "ambientLevel": case "ambientDayLevel": return values.getProperty(key, "7");
            case "ambientNightLevel": return values.getProperty(key, "4");
            case "ambientClimateLevel": case "ambientWeatherLevel":
            case "ambientMusicLevel": return values.getProperty(key, "6");
            case "ambientWeatherColdC": return values.getProperty(key, "50");
            case "ambientWeatherHotC": return values.getProperty(key, "270");
            case "ambientSunsetFade": return values.getProperty(key, "true");
            case "ambientMusicStyle": return values.getProperty(key, "pulse");
            case "ambientMusicDrive": return values.getProperty(key, "pause");
            default: return values.getProperty(key, "false");
        }
    }
    public boolean changedFrom(Config previous, String key) {
        return previous == null || !get(key).equals(previous.get(key));
    }
    /** Tweaks that change system files and take effect at the next head-unit restart. */
    public static final String[] FILE_IDS = {"consent", "tracks", "nav", "popover"};
    public boolean staticChanged(Config previous) {
        for (String id : FILE_IDS) if (changedFrom(previous, id)) return true;
        return false;
    }
    public boolean dstChanged(Config previous) { return changedFrom(previous, "dst") || changedFrom(previous, "region"); }
    public boolean needsVehicleCheck(Config previous) {
        return (enabled("dst") && dstChanged(previous)) || (enabled("heaters") && changedFrom(previous, "heaters"))
            || (enabled("tracks") && changedFrom(previous, "tracks")) || (enabled("sync") && changedFrom(previous, "sync"))
            || (enabled("driveronly") && changedFrom(previous, "driveronly"))
            || (enabled("ambient") && changedFrom(previous, "ambient"));
    }
    public boolean needsDualZoneCheck(Config previous) {
        return (enabled("sync") && changedFrom(previous, "sync")) || (enabled("driveronly") && changedFrom(previous, "driveronly"));
    }
    public boolean sameSettings(Config other) {
        for (String id : IDS) if (changedFrom(other, id)) return false;
        for (String key : HEATER_OPTIONS) if (changedFrom(other, key)) return false;
        for (String key : AMBIENT_OPTIONS) if (changedFrom(other, key)) return false;
        return !changedFrom(other, "region") && !changedFrom(other, "homeLat") && !changedFrom(other, "homeLon");
    }
    public boolean hasHome() { return !get("homeLat").isEmpty() && !get("homeLon").isEmpty(); }
    public void validateHome() {
        if (get("homeLat").isEmpty() != get("homeLon").isEmpty()) throw new IllegalArgumentException("Both Home coordinates are required");
        if (hasHome() && (!VehicleLocation.coordinate(Double.parseDouble(get("homeLat")), 90)
            || !VehicleLocation.coordinate(Double.parseDouble(get("homeLon")), 180))) throw new IllegalArgumentException("Invalid Home coordinates");
    }
    public int number(String key) { return Integer.parseInt(get(key)); }
    public boolean checked(String key) { return "true".equals(get(key)); }
    public void set(String key, String value) {
        if (isId(key) || key.equals("driver") || key.equals("passenger") || key.equals("wheel") || key.equals("ambientSunsetFade")) {
            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Expected on or off");
        } else switch (key) {
            case "region":
                if (!value.equals("us") && !value.equals("eu") && !value.equals("none")) throw new IllegalArgumentException("Unknown DST region");
                break;
            case "units":
                if (!value.equals("C") && !value.equals("F")) throw new IllegalArgumentException("Unknown temperature unit");
                break;
            case "homeLat": case "homeLon":
                if (!value.isEmpty() && !VehicleLocation.coordinate(Double.parseDouble(value), key.equals("homeLat") ? 90 : 180))
                    throw new IllegalArgumentException("Invalid Home coordinate");
                break;
            case "threshold": case "ambientWeatherColdC": case "ambientWeatherHotC":
                range(value, -400, 600); break;
            case "driverLevel": case "passengerLevel": range(value, 1, 3); break;
            case "ambientMode":
                if (!value.equals("off") && !value.equals("manual") && !value.equals("daynight")
                    && !value.equals("climate") && !value.equals("weather") && !value.equals("music"))
                    throw new IllegalArgumentException("Unknown ambient mode");
                break;
            case "ambientEquipment":
                if (!value.equals("auto") && !value.equals("fitted") && !value.equals("not_fitted")) throw new IllegalArgumentException("Unknown ambient equipment option");
                break;
            case "ambientMusicStyle":
                if (!value.equals("pulse") && !value.equals("spectrum")) throw new IllegalArgumentException("Unknown music lighting style");
                break;
            case "ambientMusicDrive":
                if (!value.equals("pause") && !value.equals("cycle")) throw new IllegalArgumentException("Unknown Drive behavior");
                break;
            case "ambientColor": case "ambientDayColor": case "ambientNightColor":
            case "ambientClimateHeatColor": case "ambientClimateCoolColor":
            case "ambientClimateDefrostColor": case "ambientClimateOffColor":
            case "ambientWeatherClearColor": case "ambientWeatherCloudyColor":
            case "ambientWeatherRainColor": case "ambientWeatherSnowColor":
            case "ambientWeatherColdColor": case "ambientWeatherMildColor": case "ambientWeatherHotColor":
                AmbientPolicy.parseHex(value);
                value = AmbientPolicy.formatHex(AmbientPolicy.parseHex(value));
                break;
            case "ambientLevel": case "ambientDayLevel": case "ambientNightLevel":
            case "ambientClimateLevel": case "ambientWeatherLevel":
                range(value, 0, 10); break;
            case "ambientMusicLevel":
                range(value, 1, 10); break;
            default: throw new IllegalArgumentException("Unknown setting: " + key);
        }
        values.setProperty(key, value);
    }
    public static boolean isId(String key) { for (String id : IDS) if (id.equals(key)) return true; return false; }
    public static final String[] HEATER_OPTIONS = {"threshold", "units", "driver", "wheel", "passenger", "driverLevel", "passengerLevel"};
    public static final String[] AMBIENT_OPTIONS = {
        "ambientMode", "ambientEquipment", "ambientColor", "ambientLevel",
        "ambientDayColor", "ambientDayLevel", "ambientNightColor", "ambientNightLevel", "ambientSunsetFade",
        "ambientClimateHeatColor", "ambientClimateCoolColor", "ambientClimateDefrostColor", "ambientClimateOffColor", "ambientClimateLevel",
        "ambientWeatherClearColor", "ambientWeatherCloudyColor", "ambientWeatherRainColor", "ambientWeatherSnowColor",
        "ambientWeatherColdColor", "ambientWeatherMildColor", "ambientWeatherHotColor", "ambientWeatherColdC", "ambientWeatherHotC", "ambientWeatherLevel",
        "ambientMusicStyle", "ambientMusicDrive", "ambientMusicLevel"
    };
    public static boolean isHeaterOption(String key) {
        for (String option : HEATER_OPTIONS) if (option.equals(key)) return true;
        return false;
    }
    private static void range(String value, int min, int max) {
        int n = Integer.parseInt(value);
        if (n < min || n > max) throw new IllegalArgumentException("Value outside " + min + "…" + max);
    }
}
