package com.codexrisk.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RiskUpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "CodexRiskWidget";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.i(TAG, "Received legacy scheduled refresh broadcast: " + action);
        RiskRefreshScheduler.schedule(context);
        Context appContext = context.getApplicationContext();
        RiskWidgetProvider.renderCached(appContext);
        RiskRefreshScheduler.scheduleOneOffJob(appContext, "legacy-" + action);
        Log.i(TAG, "Forwarded legacy scheduled refresh to job: " + action);
    }
}
