package com.codexrisk.widget;

import org.json.JSONObject;

final class WeatherDay {
    final String date;
    final int code;
    final String condition;
    final double tempMinC;
    final double tempMaxC;
    final int precipitationProbability;

    WeatherDay(String date, int code, String condition, double tempMinC, double tempMaxC, int precipitationProbability) {
        this.date = date;
        this.code = code;
        this.condition = condition;
        this.tempMinC = tempMinC;
        this.tempMaxC = tempMaxC;
        this.precipitationProbability = precipitationProbability;
    }

    JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("date", date);
        object.put("code", code);
        object.put("condition", condition);
        object.put("tempMinC", tempMinC);
        object.put("tempMaxC", tempMaxC);
        object.put("precipitationProbability", precipitationProbability);
        return object;
    }

    static WeatherDay fromJson(JSONObject object) {
        return new WeatherDay(
                object.optString("date", ""),
                object.optInt("code", 0),
                WeatherCodes.condition(object.optInt("code", 0)),
                object.optDouble("tempMinC", 0),
                object.optDouble("tempMaxC", 0),
                object.optInt("precipitationProbability", 0)
        );
    }

    String shortText() {
        String temp = Math.round(tempMinC) + "-" + Math.round(tempMaxC) + "C";
        if (precipitationProbability > 0) {
            return condition + " " + temp + " 降水" + precipitationProbability + "%";
        }
        return condition + " " + temp;
    }

    String compactText(String prefix) {
        String temp = Math.round(tempMinC) + "-" + Math.round(tempMaxC) + "C";
        String rain = precipitationProbability > 0 ? " " + precipitationProbability + "%" : "";
        String label = prefix == null || prefix.isEmpty() ? "" : prefix + " ";
        return label + condition + " " + temp + rain;
    }
}
