package com.codexrisk.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RiskWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.codexrisk.widget.REFRESH";
    static final String ACTION_TEST_DAILY_ALARM = "com.codexrisk.widget.TEST_DAILY_ALARM";
    private static final String TAG = "CodexRiskWidget";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        RiskRefreshScheduler.schedule(context);
        RiskRefreshScheduler.scheduleOneOffJob(context);
        renderCached(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_TEST_DAILY_ALARM.equals(action)) {
            RiskRefreshScheduler.schedule(context);
            RiskRefreshScheduler.scheduleTestDailyAlarm(context, 10_000L);
            renderCached(context);
            return;
        }
        if (ACTION_REFRESH.equals(action) || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            RiskRefreshScheduler.schedule(context);
            RiskRefreshScheduler.scheduleOneOffJob(context, action);
            PendingResult pendingResult = goAsync();
            renderCached(context);
            pendingResult.finish();
            return;
        }
        if (isScheduledRefreshAction(action)) {
            handleScheduledRefresh(context, action);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        RiskRefreshScheduler.schedule(context);
        RiskRefreshScheduler.scheduleOneOffJob(context);
        renderCached(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        RiskRefreshScheduler.cancel(context);
    }

    static void requestUpdate(Context context) {
        requestUpdate(context, "widget", null);
    }

    static void requestUpdate(Context context, String reason) {
        requestUpdate(context, reason, null);
    }

    static void requestUpdate(Context context, Runnable onComplete) {
        requestUpdate(context, "widget", onComplete);
    }

    static void requestUpdate(Context context, String reason, Runnable onComplete) {
        Context appContext = context.getApplicationContext();
        renderCached(appContext);
        RiskRefreshScheduler.scheduleOneOffJob(appContext, reason);
        if (onComplete != null) {
            onComplete.run();
        }
    }

    static void renderCached(Context context) {
        Context appContext = context.getApplicationContext();
        RiskSnapshot cachedRisk = WidgetPrefs.loadRisk(appContext);
        if (cachedRisk == null) {
            render(appContext, null, "首次更新中...");
        } else {
            render(appContext, cachedRisk, cachedStatus(cachedRisk));
        }
    }

    static void render(Context context, RiskSnapshot snapshot, String overrideStatus) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, RiskWidgetProvider.class));
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.risk_widget);
        Bitmap background = WidgetBackground.create(context);
        views.setImageViewBitmap(R.id.widget_background_image, background);
        views.setTextViewText(R.id.widget_title, "高志凯美股崩盘概率");
        views.setTextViewText(R.id.widget_score, snapshot == null ? "--" : String.valueOf(snapshot.riskScore));
        views.setTextColor(R.id.widget_score, riskColor(snapshot == null ? 0 : snapshot.riskScore));
        views.setTextViewText(R.id.widget_stage, snapshot == null ? "等待更新" : snapshot.stage);
        views.setTextViewText(R.id.widget_action, snapshot == null ? "等待服务端生成今日预测" : firstNonEmpty(snapshot.keyTrigger, snapshot.action));
        views.setTextViewText(R.id.widget_updated, overrideStatus != null ? overrideStatus : (snapshot == null ? "等待更新" : snapshot.updatedText()));
        views.setTextViewText(R.id.widget_weather_today, snapshot == null ? "OpenAI 版" : firstNonEmpty(snapshot.summary, "OpenAI 版"));
        views.setTextViewText(R.id.widget_weather_tomorrow, snapshot == null ? "08:00 前自动刷新" : firstNonEmpty(snapshot.model, "08:00 前自动刷新"));
        views.setOnClickPendingIntent(R.id.widget_root, settingsIntent(context));
        manager.updateAppWidget(ids, views);
        Log.i(TAG, "Updated widget views for " + ids.length + " widget(s)");
    }

    private static boolean isScheduledRefreshAction(String action) {
        return RiskRefreshScheduler.ACTION_PRE_TARGET_REFRESH.equals(action)
                || RiskRefreshScheduler.ACTION_VISIBLE_EDGE_REFRESH.equals(action)
                || RiskRefreshScheduler.ACTION_DAILY_REFRESH.equals(action)
                || RiskRefreshScheduler.ACTION_FALLBACK_REFRESH.equals(action)
                || RiskRefreshScheduler.ACTION_RETRY_REFRESH.equals(action);
    }

    private void handleScheduledRefresh(Context context, String action) {
        Context appContext = context.getApplicationContext();
        Log.i(TAG, "Received scheduled provider refresh broadcast: " + action);
        RiskRefreshScheduler.schedule(appContext);
        renderCached(appContext);
        RiskRefreshScheduler.scheduleOneOffJob(appContext, action);
        Log.i(TAG, "Delegated scheduled provider refresh to JobScheduler: " + action);
    }

    private static PendingIntent settingsIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static int riskColor(int score) {
        int clamped = Math.max(0, Math.min(100, score));
        if (clamped <= 50) {
            return interpolateColor(
                    Color.rgb(62, 220, 143),
                    Color.rgb(242, 201, 76),
                    clamped / 50f
            );
        }
        return interpolateColor(
                Color.rgb(242, 201, 76),
                Color.rgb(217, 65, 65),
                (clamped - 50) / 50f
        );
    }

    private static int interpolateColor(int start, int end, float fraction) {
        float safeFraction = Math.max(0f, Math.min(1f, fraction));
        int red = Math.round(Color.red(start) + (Color.red(end) - Color.red(start)) * safeFraction);
        int green = Math.round(Color.green(start) + (Color.green(end) - Color.green(start)) * safeFraction);
        int blue = Math.round(Color.blue(start) + (Color.blue(end) - Color.blue(start)) * safeFraction);
        return Color.rgb(red, green, blue);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
    }

    private static String cachedStatus(RiskSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        if (snapshot.isPending()) {
            return snapshot.updatedText() + " · 今日评估生成中";
        }
        if (!snapshot.isCodexFinal()) {
            return snapshot.updatedText() + " · 深度评估继续更新";
        }
        return snapshot.updatedText();
    }
}
