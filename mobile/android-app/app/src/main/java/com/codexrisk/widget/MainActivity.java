package com.codexrisk.widget;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String DASHBOARD_URL = "http://[2402:4e00:c013:8600:5602:3dc2:a2d0:0]/us-stock-20260629/crash-monitor/";

    private TextView probabilityView;
    private TextView stageView;
    private TextView triggerView;
    private TextView summaryView;
    private TextView statusView;
    private TextView exactAlarmHint;
    private Button exactAlarmButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        probabilityView = findViewById(R.id.hero_probability);
        stageView = findViewById(R.id.hero_stage);
        triggerView = findViewById(R.id.hero_trigger);
        summaryView = findViewById(R.id.hero_summary);
        statusView = findViewById(R.id.main_status);
        exactAlarmHint = findViewById(R.id.exact_alarm_hint);
        exactAlarmButton = findViewById(R.id.exact_alarm_button);
        Button refreshButton = findViewById(R.id.refresh_button);
        Button openDashboardButton = findViewById(R.id.open_dashboard_button);
        Button pinWidgetButton = findViewById(R.id.pin_widget_button);

        RiskRefreshScheduler.schedule(this);
        bindSnapshot(WidgetPrefs.loadRisk(this));
        refreshExactAlarmUi();

        exactAlarmButton.setOnClickListener(view -> openExactAlarmSettings());
        refreshButton.setOnClickListener(view -> {
            RiskRefreshScheduler.schedule(this);
            RiskWidgetProvider.requestUpdate(this, "manual-openai-refresh");
            updateStatus("已请求立即刷新，挂件会自动同步");
            refreshSnapshotAsync("manual-openai-refresh");
        });
        openDashboardButton.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD_URL));
            startActivity(intent);
        });
        pinWidgetButton.setOnClickListener(view -> requestPinWidget());
        RiskWidgetProvider.requestUpdate(this, "main-activity-open");
        refreshSnapshotAsync("main-activity-open");
    }

    @Override
    protected void onResume() {
        super.onResume();
        RiskRefreshScheduler.schedule(this);
        bindSnapshot(WidgetPrefs.loadRisk(this));
        refreshExactAlarmUi();
        refreshSnapshotAsync("resume");
    }

    private void bindSnapshot(RiskSnapshot snapshot) {
        if (snapshot == null) {
            probabilityView.setText("--");
            stageView.setText("等待今日预测");
            triggerView.setText("关键触发因素：等待服务端生成今日结果");
            summaryView.setText("挂件会在每天早上 7:55 开始自动刷新，不需要手动打开 App。");
            updateStatus("当前暂无本地缓存，正在等待首次同步。");
            return;
        }
        probabilityView.setText(snapshot.riskScore + "%");
        stageView.setText(snapshot.stage);
        triggerView.setText("关键触发因素：" + safe(snapshot.keyTrigger, "等待数据"));
        summaryView.setText(safe(snapshot.summary, safe(snapshot.action, "等待服务端生成今日结果。")));
        StringBuilder builder = new StringBuilder();
        builder.append("最近更新：").append(snapshot.updatedText());
        if (snapshot.model != null && !snapshot.model.isEmpty()) {
            builder.append("\n模型：").append(snapshot.model);
        }
        if (snapshot.analysisMode != null && !snapshot.analysisMode.isEmpty()) {
            builder.append("\n模式：").append(snapshot.analysisMode);
        }
        if (snapshot.assessmentStatus != null && !snapshot.assessmentStatus.isEmpty()) {
            builder.append("\n状态：").append(snapshot.assessmentStatus);
        }
        updateStatus(builder.toString());
    }

    private void refreshExactAlarmUi() {
        boolean needsPermission = needsExactAlarmPermission();
        exactAlarmHint.setVisibility(needsPermission ? TextView.VISIBLE : TextView.GONE);
        exactAlarmButton.setVisibility(needsPermission ? Button.VISIBLE : Button.GONE);
    }

    private boolean needsExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && !alarmManager.canScheduleExactAlarms();
    }

    private void openExactAlarmSettings() {
        if (!needsExactAlarmPermission()) {
            updateStatus("精确定时已可用，挂件会在早晨自动刷新。");
            refreshExactAlarmUi();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(intent);
            updateStatus("请允许精确定时，保证每天早上 7:55 完成刷新。");
        } catch (Exception ignored) {
            updateStatus("请在系统设置中手动允许精确定时。");
        }
    }

    private void updateStatus(String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
    }

    private void refreshSnapshotAsync(String reason) {
        new Thread(() -> {
            try {
                RiskSnapshot snapshot = RiskRepository.fetchLatest();
                WidgetPrefs.saveRisk(this, snapshot);
                RiskWidgetProvider.render(this, snapshot, null);
                runOnUiThread(() -> {
                    bindSnapshot(snapshot);
                    updateStatus("最近更新：" + snapshot.updatedText()
                            + "\n来源：" + safe(snapshot.source, "openai_dashboard")
                            + "\n状态：" + safe(snapshot.assessmentStatus, "final"));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    bindSnapshot(WidgetPrefs.loadRisk(this));
                    updateStatus("自动同步未完成，将继续显示本地缓存。\n原因：" + error.getClass().getSimpleName()
                            + "\n触发：" + reason);
                });
            }
        }, "CodexRiskMainRefresh").start();
    }

    private void requestPinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager == null || !manager.isRequestPinAppWidgetSupported()) {
            updateStatus("当前桌面不支持一键添加挂件，请长按桌面手动添加“高志凯美股崩盘概率”挂件。");
            return;
        }
        PendingIntent callback = PendingIntent.getBroadcast(
                this,
                2001,
                new Intent(this, RiskWidgetProvider.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        boolean requested = manager.requestPinAppWidget(
                new ComponentName(this, RiskWidgetProvider.class),
                null,
                callback
        );
        if (requested) {
            updateStatus("已请求把桌面挂件固定到主屏。请在系统弹窗里确认添加。");
        } else {
            updateStatus("桌面没有接受挂件固定请求，请长按桌面手动添加。");
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
