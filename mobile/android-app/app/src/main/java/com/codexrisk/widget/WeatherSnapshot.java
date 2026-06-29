package com.codexrisk.widget;

import org.json.JSONArray;
import org.json.JSONObject;

final class WeatherSnapshot {
    final String modeLabel;
    final String source;
    final String updatedAt;
    final WeatherDay today;
    final WeatherDay tomorrow;

    WeatherSnapshot(String modeLabel, String updatedAt, WeatherDay today, WeatherDay tomorrow) {
        this(modeLabel, "", updatedAt, today, tomorrow);
    }

    WeatherSnapshot(String modeLabel, String source, String updatedAt, WeatherDay today, WeatherDay tomorrow) {
        this.modeLabel = modeLabel;
        this.source = source;
        this.updatedAt = updatedAt;
        this.today = today;
        this.tomorrow = tomorrow;
    }

    JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("modeLabel", modeLabel);
        object.put("source", source);
        object.put("updatedAt", updatedAt);
        JSONArray days = new JSONArray();
        days.put(today.toJson());
        days.put(tomorrow.toJson());
        object.put("days", days);
        return object;
    }

    static WeatherSnapshot fromJson(String json) throws Exception {
        JSONObject object = new JSONObject(json);
        JSONArray days = object.getJSONArray("days");
        String modeLabel = object.optString("modeLabel", "定位天气");
        String source = object.optString("source", "");
        return new WeatherSnapshot(
                modeLabel,
                source.isEmpty() ? "Open-Meteo · " + modeLabel : source,
                object.optString("updatedAt", ""),
                WeatherDay.fromJson(days.getJSONObject(0)),
                WeatherDay.fromJson(days.getJSONObject(1))
        );
    }

    String displayText() {
        if (today == null || tomorrow == null) {
            return sourceLabel();
        }
        return sourceLabel() + "  今 " + today.shortText() + " / 明 " + tomorrow.shortText();
    }

    String sourceLabel() {
        if (source != null && !source.isEmpty()) {
            return source;
        }
        return "Open-Meteo · " + modeLabel;
    }
}
