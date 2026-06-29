package com.codexrisk.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CodexRiskWidget";
    private static final String ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            Log.i(TAG, "Scheduling widget refresh after " + action);
            RiskRefreshScheduler.schedule(context);
            RiskRefreshScheduler.scheduleOneOffJob(context, action);
            RiskWidgetProvider.renderCached(context);
        }
    }
}
