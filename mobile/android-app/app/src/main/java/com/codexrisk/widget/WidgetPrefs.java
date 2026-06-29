package com.codexrisk.widget;

import android.content.Context;
import android.content.SharedPreferences;

final class WidgetPrefs {
    private static final String PREFS = "risk_widget_prefs";
    private static final String KEY_WEATHER_LOCATION = "weather_location";
    private static final String KEY_WEATHER_CACHE = "weather_cache";
    private static final String KEY_RISK_CACHE = "risk_cache";
    private static final String KEY_LAST_LATITUDE = "last_latitude";
    private static final String KEY_LAST_LONGITUDE = "last_longitude";
    private static final String KEY_LAST_ATTEMPT_AT = "last_attempt_at";
    private static final String KEY_LAST_ATTEMPT_REASON = "last_attempt_reason";
    private static final String KEY_LAST_REFRESH_AT = "last_refresh_at";
    private static final String KEY_LAST_REFRESH_OK = "last_refresh_ok";
    private static final String KEY_LAST_REFRESH_ERROR = "last_refresh_error";
    private static final String KEY_LAST_RISK_DATE = "last_risk_date";
    private static final String KEY_LAST_RISK_UPDATED = "last_risk_updated";
    private static final String KEY_LAST_WEATHER_SOURCE = "last_weather_source";

    private WidgetPrefs() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static WeatherLocation weatherLocation(Context context) {
        return WeatherLocation.byId(prefs(context).getString(KEY_WEATHER_LOCATION, WeatherLocation.DEVICE.id));
    }

    static void setWeatherLocation(Context context, String id) {
        prefs(context).edit().putString(KEY_WEATHER_LOCATION, id).apply();
    }

    static void saveWeather(Context context, WeatherSnapshot snapshot) {
        try {
            prefs(context).edit().putString(KEY_WEATHER_CACHE, snapshot.toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void saveLastDeviceLocation(Context context, double latitude, double longitude) {
        prefs(context).edit()
                .putFloat(KEY_LAST_LATITUDE, (float) latitude)
                .putFloat(KEY_LAST_LONGITUDE, (float) longitude)
                .apply();
    }

    static boolean hasLastDeviceLocation(Context context) {
        return prefs(context).contains(KEY_LAST_LATITUDE) && prefs(context).contains(KEY_LAST_LONGITUDE);
    }

    static double lastLatitude(Context context) {
        return prefs(context).getFloat(KEY_LAST_LATITUDE, 0f);
    }

    static double lastLongitude(Context context) {
        return prefs(context).getFloat(KEY_LAST_LONGITUDE, 0f);
    }

    static WeatherSnapshot loadWeather(Context context) {
        String json = prefs(context).getString(KEY_WEATHER_CACHE, "");
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return WeatherSnapshot.fromJson(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    static void saveRisk(Context context, RiskSnapshot snapshot) {
        try {
            RiskSnapshot existing = loadRisk(context);
            if (snapshot != null && !snapshot.isNewerThan(existing)) {
                return;
            }
            prefs(context).edit().putString(KEY_RISK_CACHE, snapshot.toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static RiskSnapshot loadRisk(Context context) {
        String json = prefs(context).getString(KEY_RISK_CACHE, "");
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return RiskSnapshot.fromJson(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    static void markRefreshAttempt(Context context, long timestampMillis, String reason) {
        prefs(context).edit()
                .putLong(KEY_LAST_ATTEMPT_AT, timestampMillis)
                .putString(KEY_LAST_ATTEMPT_REASON, reason == null ? "" : reason)
                .apply();
    }

    static void markRefreshResult(
            Context context,
            long timestampMillis,
            boolean success,
            String error,
            String riskDate,
            String riskUpdated,
            String weatherSource
    ) {
        prefs(context).edit()
                .putLong(KEY_LAST_REFRESH_AT, timestampMillis)
                .putBoolean(KEY_LAST_REFRESH_OK, success)
                .putString(KEY_LAST_REFRESH_ERROR, error == null ? "" : error)
                .putString(KEY_LAST_RISK_DATE, riskDate == null ? "" : riskDate)
                .putString(KEY_LAST_RISK_UPDATED, riskUpdated == null ? "" : riskUpdated)
                .putString(KEY_LAST_WEATHER_SOURCE, weatherSource == null ? "" : weatherSource)
                .apply();
    }

    static String lastRefreshError(Context context) {
        return prefs(context).getString(KEY_LAST_REFRESH_ERROR, "");
    }
}
