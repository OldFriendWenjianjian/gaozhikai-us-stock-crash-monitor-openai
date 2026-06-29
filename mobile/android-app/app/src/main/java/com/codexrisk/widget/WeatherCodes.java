package com.codexrisk.widget;

import android.graphics.Color;

final class WeatherCodes {
    private WeatherCodes() {
    }

    static String condition(int code) {
        if (code == 0) return "晴";
        if (code == 1 || code == 2) return "少云";
        if (code == 3) return "多云";
        if (code == 45 || code == 48) return "雾";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 95) return "雷雨";
        return "天气";
    }

    static int color(int code) {
        if (code == 0) return Color.rgb(77, 166, 255);
        if (code == 1 || code == 2) return Color.rgb(83, 144, 194);
        if (code == 3) return Color.rgb(92, 112, 130);
        if (code == 45 || code == 48) return Color.rgb(116, 126, 130);
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return Color.rgb(49, 98, 143);
        if (code >= 71 && code <= 77) return Color.rgb(143, 180, 202);
        if (code >= 95) return Color.rgb(70, 64, 116);
        return Color.rgb(23, 32, 38);
    }
}
