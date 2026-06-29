package com.codexrisk.widget;

import org.json.JSONObject;

final class RiskSnapshot {
    final int riskScore;
    final String date;
    final String time;
    final String stage;
    final String action;
    final String keyTrigger;
    final String confidence;
    final String source;
    final String assessmentStatus;
    final String analysisMode;
    final String summary;
    final String model;
    final String updatedAt;

    RiskSnapshot(
            int riskScore,
            String date,
            String time,
            String stage,
            String action,
            String keyTrigger,
            String confidence,
            String source,
            String assessmentStatus,
            String analysisMode,
            String summary,
            String model,
            String updatedAt
    ) {
        this.riskScore = riskScore;
        this.date = date;
        this.time = time;
        this.stage = stage;
        this.action = action;
        this.keyTrigger = keyTrigger;
        this.confidence = confidence;
        this.source = source;
        this.assessmentStatus = assessmentStatus;
        this.analysisMode = analysisMode;
        this.summary = summary;
        this.model = model;
        this.updatedAt = updatedAt;
    }

    static RiskSnapshot fromJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        return new RiskSnapshot(
                root.optInt("riskScore", 0),
                root.optString("date", ""),
                root.optString("time", ""),
                root.optString("stage", "等待更新"),
                root.optString("action", root.optString("summary", "")),
                root.optString("keyTrigger", ""),
                root.optString("confidence", ""),
                root.optString("source", "openai_dashboard"),
                root.optString("assessmentStatus", "final"),
                root.optString("analysisMode", "standard"),
                root.optString("summary", ""),
                root.optString("model", ""),
                root.optString("updatedAt", "")
        );
    }

    JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("riskScore", riskScore);
        object.put("date", date);
        object.put("time", time);
        object.put("stage", stage);
        object.put("action", action);
        object.put("keyTrigger", keyTrigger);
        object.put("confidence", confidence);
        object.put("source", source);
        object.put("assessmentStatus", assessmentStatus);
        object.put("analysisMode", analysisMode);
        object.put("summary", summary);
        object.put("model", model);
        object.put("updatedAt", updatedAt);
        return object;
    }

    String updatedText() {
        String shortDate = date;
        if (shortDate.length() == 10) {
            shortDate = shortDate.substring(5);
        }
        String shortTime = time;
        if (shortTime.length() >= 5) {
            shortTime = shortTime.substring(0, 5);
        }
        if (!shortDate.isEmpty() && !shortTime.isEmpty()) {
            return "更新 " + shortDate + " " + shortTime;
        }
        if (!shortDate.isEmpty()) {
            return "更新 " + shortDate;
        }
        return "等待更新";
    }

    boolean isPending() {
        return "pending".equalsIgnoreCase(assessmentStatus);
    }

    boolean isCodexFinal() {
        return !isPending() && source != null && source.startsWith("codex_cli");
    }

    long freshnessRank() {
        long dateValue = parseDigits(date);
        long timeValue = parseDigits(time);
        long statusValue = isPending() ? 0L : 1L;
        long sourceValue = isCodexFinal() ? 1L : 0L;
        return dateValue * 100_000_000L + timeValue * 100L + statusValue * 10L + sourceValue;
    }

    boolean isNewerThan(RiskSnapshot other) {
        if (other == null) {
            return true;
        }
        return freshnessRank() >= other.freshnessRank();
    }

    private static long parseDigits(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
