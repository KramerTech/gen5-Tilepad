package com.tilepad.tweakcore;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit modern civil schedules; independent of KitKat's old timezone database. */
public final class DstRules {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    public static int baseOffsetMinutes(String zone) {
        Matcher m = Pattern.compile("Mobis/UTC([+-])(\\d{2}):(\\d{2})").matcher(zone);
        if (!m.matches()) throw new IllegalArgumentException("Select a fixed Mobis timezone in Hyundai Date/Time settings first.");
        int hours = Integer.parseInt(m.group(2)), minutes = Integer.parseInt(m.group(3));
        if (hours > 14 || minutes > 59 || (hours == 14 && minutes != 0)) throw new IllegalArgumentException("Invalid timezone offset");
        return (hours * 60 + minutes) * (m.group(1).equals("-") ? -1 : 1);
    }
    public static boolean active(long now, int offsetMinutes, String region) {
        if (region.equals("none")) return false;
        int year = year(now);
        long[] t = transitions(year, offsetMinutes, region);
        return now >= t[0] && now < t[1];
    }
    public static long next(long now, int offsetMinutes, String region) {
        if (region.equals("none")) return Long.MAX_VALUE;
        int year = year(now);
        for (int y = year; y <= year + 1; y++)
            for (long t : transitions(y, offsetMinutes, region)) if (t > now) return t;
        throw new IllegalStateException("Missing DST transition");
    }
    private static int year(long now) { Calendar c = new GregorianCalendar(UTC); c.setTimeInMillis(now); return c.get(Calendar.YEAR); }
    private static long[] transitions(int year, int offset, String region) {
        if (region.equals("us")) return new long[]{sunday(year, Calendar.MARCH, 2, 2) - offset * 60000L,
            sunday(year, Calendar.NOVEMBER, 1, 2) - (offset + 60) * 60000L};
        if (region.equals("eu")) return new long[]{sunday(year, Calendar.MARCH, -1, 1), sunday(year, Calendar.OCTOBER, -1, 1)};
        throw new IllegalArgumentException("Unknown DST region");
    }
    private static long sunday(int year, int month, int week, int hour) {
        Calendar c = new GregorianCalendar(UTC); c.clear(); c.set(year, month, 1, hour, 0, 0);
        if (week < 0) {
            c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
            c.add(Calendar.DAY_OF_MONTH, -(c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY));
        } else c.add(Calendar.DAY_OF_MONTH, (8 - c.get(Calendar.DAY_OF_WEEK)) % 7 + (week - 1) * 7);
        return c.getTimeInMillis();
    }
    private DstRules() { }
}
