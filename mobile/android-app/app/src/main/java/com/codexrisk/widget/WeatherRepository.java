package com.codexrisk.widget;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class WeatherRepository {
    private static final WeatherLocation DEFAULT_FALLBACK = WeatherLocation.PRESETS[4];

    private WeatherRepository() {
    }

    static WeatherSnapshot fetch(Context context) throws Exception {
        WeatherLocation selected = WidgetPrefs.weatherLocation(context);
        double latitude = selected.latitude;
        double longitude = selected.longitude;
        String label = selected.label;
        if (selected.device) {
            Location location = lastKnownLocation(context);
            if (location == null) {
                location = requestCurrentLocation(context);
            }
            if (location != null) {
                latitude = location.getLatitude();
                longitude = location.getLongitude();
                label = "当前位置";
                WidgetPrefs.saveLastDeviceLocation(context, latitude, longitude);
            } else if (WidgetPrefs.hasLastDeviceLocation(context)) {
                latitude = WidgetPrefs.lastLatitude(context);
                longitude = WidgetPrefs.lastLongitude(context);
                label = "上次定位";
            } else {
                latitude = DEFAULT_FALLBACK.latitude;
                longitude = DEFAULT_FALLBACK.longitude;
                label = "默认" + DEFAULT_FALLBACK.label;
            }
        }
        String url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=2&timezone=Asia%%2FShanghai",
                latitude,
                longitude
        );
        JSONObject root = new JSONObject(get(url));
        JSONObject daily = root.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        JSONArray pop = daily.optJSONArray("precipitation_probability_max");
        WeatherDay today = dayAt(dates, codes, min, max, pop, 0);
        WeatherDay tomorrow = dayAt(dates, codes, min, max, pop, 1);
        return new WeatherSnapshot(label, "Open-Meteo · " + label, nowText(), today, tomorrow);
    }

    private static WeatherDay dayAt(JSONArray dates, JSONArray codes, JSONArray min, JSONArray max, JSONArray pop, int index) throws Exception {
        int code = codes.optInt(index, 0);
        int precipitation = pop == null ? 0 : pop.optInt(index, 0);
        return new WeatherDay(
                dates.optString(index, ""),
                code,
                WeatherCodes.condition(code),
                min.optDouble(index, 0),
                max.optDouble(index, 0),
                precipitation
        );
    }

    private static Location lastKnownLocation(Context context) {
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }
        Location best = null;
        for (String provider : manager.getProviders(true)) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (SecurityException ignored) {
                return null;
            }
        }
        return best;
    }

    private static Location requestCurrentLocation(Context context) {
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }
        String provider = null;
        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else {
                for (String candidate : manager.getProviders(true)) {
                    provider = candidate;
                    break;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        if (provider == null) {
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Location> result = new AtomicReference<>();
        LocationListener listener = location -> {
            result.set(location);
            latch.countDown();
        };
        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            latch.await(8, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                manager.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
        return result.get();
    }

    private static String get(String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "CodexRiskWidgetAndroid/1.1");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("weather HTTP " + code);
            }
            try (InputStream input = connection.getInputStream()) {
                StringBuilder builder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line).append('\n');
                    }
                }
                return builder.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String nowText() {
        try {
            return OffsetDateTime.now().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
