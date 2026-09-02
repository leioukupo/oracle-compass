package com.magneo.compass;

import java.util.Calendar;

/** Shared time-slot calculations for the Canvas and OpenGL compass renderers. */
final class TimeRitual {
    static final long PULSE_MS = 900L;

    private TimeRitual() {}

    static int hourZhi(Calendar cal) {
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return ((hour + 1) / 2) % 12;
    }

    static int hourGan(Calendar cal, int hourZhi) {
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int a = (14 - month) / 12;
        int y2 = year + 4800 - a;
        int m = month + 12 * a - 3;
        int jdn = day + (153 * m + 2) / 5 + 365 * y2 + y2 / 4
                - y2 / 100 + y2 / 400 - 32045;
        int dayGan = ((jdn + 9) % 10 + 10) % 10;
        return ((dayGan % 5) * 2 + hourZhi) % 10;
    }

    /** Returns a monotonic local-time slot key with boundaries at 01:00, 03:00, ... . */
    static long slotKey(Calendar cal) {
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        long day = cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR);
        if (hour == 0) day--;
        int adjustedHour = hour == 0 ? 23 : hour - 1;
        return day * 12L + adjustedHour / 2;
    }

    static boolean isNight(Calendar cal) {
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 19 || hour < 7;
    }
}
