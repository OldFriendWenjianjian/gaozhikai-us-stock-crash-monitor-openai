package com.codexrisk.widget;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class WidgetClock {
    static final TimeZone CHINA_TIME_ZONE = TimeZone.getTimeZone("Asia/Shanghai");

    private WidgetClock() {
    }

    static Calendar chinaCalendar() {
        return Calendar.getInstance(CHINA_TIME_ZONE);
    }

    static String chinaTodayText() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(CHINA_TIME_ZONE);
        return format.format(new Date());
    }
}
