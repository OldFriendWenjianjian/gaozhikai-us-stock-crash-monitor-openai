package com.codexrisk.widget;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class RiskRefreshRunner {
    private static final String TAG = "CodexRiskWidget";

    private RiskRefreshRunner() {
    }

    static Result refresh(Context context, String reason) {
        return refresh(context, reason, null);
    }

    static Result refresh(Context context, String reason, CancellationSignal cancellationSignal) {
        Context appContext = context.getApplicationContext();
        long started = System.currentTimeMillis();
        WidgetPrefs.markRefreshAttempt(appContext, started, reason);
        RiskSnapshot cachedRisk = WidgetPrefs.loadRisk(appContext);
        RiskSnapshot risk = cachedRisk;
        String error = "";
        boolean riskOk = false;
        boolean riskRendered = false;
        Log.i(TAG, "Refresh job started: " + reason);
        try {
            RiskSnapshot fetchedRisk = RiskRepository.fetchLatest();
            if (isCancelled(cancellationSignal)) {
                Log.i(TAG, "Refresh job cancelled after risk fetch: " + reason);
                return Result.cancelled();
            }
            riskOk = true;
            if (fetchedRisk.isNewerThan(cachedRisk)) {
                WidgetPrefs.saveRisk(appContext, fetchedRisk);
                risk = fetchedRisk;
                cachedRisk = fetchedRisk;
                render(appContext, risk, riskOk, error);
                riskRendered = true;
            } else {
                risk = cachedRisk;
                Log.i(TAG, "Ignoring stale risk snapshot: " + fetchedRisk.updatedText()
                        + " existing=" + (cachedRisk == null ? "none" : cachedRisk.updatedText()));
            }
            Log.i(TAG, "Fetched latest risk snapshot: "
                    + (risk == null ? "none" : (risk.riskScore + " " + risk.updatedText())));
        } catch (Exception riskError) {
            error = appendError(error, "risk:" + riskError.getClass().getSimpleName());
            Log.e(TAG, "Failed to fetch latest risk snapshot", riskError);
        }
        if (isCancelled(cancellationSignal)) {
            Log.i(TAG, "Refresh job cancelled before render: " + reason);
            return Result.cancelled();
        }
        if (!riskRendered) {
            render(appContext, risk, riskOk, error);
        }
        long finished = System.currentTimeMillis();
        boolean success = riskOk;
        if (isCancelled(cancellationSignal)) {
            Log.i(TAG, "Refresh job cancelled before completion bookkeeping: " + reason);
            return Result.cancelled();
        }
        boolean finalToday = isFinalToday(risk);
        WidgetPrefs.markRefreshResult(
                appContext,
                finished,
                success,
                error,
                risk == null ? "" : risk.date,
                risk == null ? "" : risk.updatedText(),
                ""
        );
        Log.i(TAG, "Refresh job finished: reason=" + reason
                + " riskOk=" + riskOk
                + " finalToday=" + finalToday
                + " riskDate=" + (risk == null ? "" : risk.date)
                + " riskSource=" + (risk == null ? "" : risk.source)
                + " assessmentStatus=" + (risk == null ? "" : risk.assessmentStatus)
                + " analysisMode=" + (risk == null ? "" : risk.analysisMode)
                + " elapsedMs=" + (finished - started));
        return new Result(success, riskOk, error, risk);
    }

    private static boolean isCancelled(CancellationSignal cancellationSignal) {
        return cancellationSignal != null && cancellationSignal.isCancelled();
    }

    private static void render(Context context, RiskSnapshot risk, boolean riskOk, String error) {
        RiskWidgetProvider.render(context, risk, statusFor(risk, riskOk, error));
    }

    private static String appendError(String existing, String next) {
        if (existing == null || existing.isEmpty()) {
            return next;
        }
        return existing + "," + next;
    }

    private static String statusFor(RiskSnapshot risk, boolean riskOk, String error) {
        if (riskOk && isFinalToday(risk)) {
            return null;
        }
        if (riskOk && isToday(risk) && risk != null && !risk.isPending()) {
            return risk.updatedText() + " · 深度评估继续更新";
        }
        if (riskOk && isToday(risk) && risk != null && risk.isPending()) {
            return risk.updatedText() + " · 今日评估生成中";
        }
        if (riskOk && risk != null) {
            return risk.updatedText() + " · 暂时显示旧数据";
        }
        if (error == null || error.isEmpty()) {
            return null;
        }
        if (risk == null) {
            return "网络不可用，等待下次刷新";
        }
        return risk.updatedText() + " · 网络异常";
    }

    private static boolean isToday(RiskSnapshot risk) {
        if (risk == null || risk.date == null || risk.date.isEmpty()) {
            return false;
        }
        return WidgetClock.chinaTodayText().equals(risk.date);
    }

    private static boolean isFinalToday(RiskSnapshot risk) {
        return isToday(risk) && risk != null && risk.isCodexFinal();
    }

    static final class Result {
        final boolean success;
        final boolean riskOk;
        final String error;
        final RiskSnapshot risk;

        Result(boolean success, boolean riskOk, String error, RiskSnapshot risk) {
            this.success = success;
            this.riskOk = riskOk;
            this.error = error;
            this.risk = risk;
        }

        boolean hasFreshRiskForToday() {
            return success && isFinalToday(risk);
        }

        static Result cancelled() {
            return new Result(false, false, "cancelled", null);
        }
    }

    interface CancellationSignal {
        boolean isCancelled();
    }
}
