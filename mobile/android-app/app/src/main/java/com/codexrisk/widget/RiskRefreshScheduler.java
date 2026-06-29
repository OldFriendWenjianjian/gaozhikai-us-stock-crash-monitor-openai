package com.codexrisk.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.Log;

import java.util.Calendar;

final class RiskRefreshScheduler {
    static final String ACTION_PRE_TARGET_REFRESH = "com.codexrisk.widget.PRE_TARGET_REFRESH";
    static final String ACTION_VISIBLE_EDGE_REFRESH = "com.codexrisk.widget.VISIBLE_EDGE_REFRESH";
    static final String ACTION_DAILY_REFRESH = "com.codexrisk.widget.DAILY_REFRESH";

    private static final String TAG = "CodexRiskWidget";
    private static final int PRE_TARGET_REQUEST_CODE = 2100;
    private static final int VISIBLE_EDGE_REQUEST_CODE = 2104;
    private static final int DAILY_REQUEST_CODE = 2101;
    private static final int FALLBACK_REQUEST_CODE = 2102;
    private static final int RETRY_REQUEST_CODE = 2103;
    private static final int TEST_DAILY_REQUEST_CODE = 2199;
    private static final int ONE_OFF_JOB_PRE_TARGET_ID = 3201;
    private static final int ONE_OFF_JOB_VISIBLE_EDGE_ID = 3202;
    private static final int ONE_OFF_JOB_DAILY_ID = 3203;
    private static final int ONE_OFF_JOB_RETRY_ID = 3204;
    private static final int ONE_OFF_JOB_MANUAL_ID = 3205;
    private static final int ONE_OFF_JOB_FALLBACK_ID = 3206;
    private static final int ONE_OFF_JOB_WEATHER_ID = 3207;
    private static final int ONE_OFF_JOB_BOOT_ID = 3208;
    private static final int ONE_OFF_JOB_LEGACY_ID = 3209;
    private static final int PRE_TARGET_HOUR = 7;
    private static final int PRE_TARGET_MINUTE = 55;
    private static final long ONE_OFF_JOB_DELAY_MS = 0L;

    private RiskRefreshScheduler() {
    }

    static void schedule(Context context) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager unavailable; widget refresh alarms not scheduled");
            return;
        }
        cancelObsoleteSchedules(appContext, alarmManager);
        cancelLegacyReceiverAlarms(alarmManager, appContext);
        scheduleAlarm(
                alarmManager,
                AlarmManager.RTC_WAKEUP,
                nextFixedTriggerMillis(PRE_TARGET_HOUR, PRE_TARGET_MINUTE),
                pendingIntent(appContext, ACTION_PRE_TARGET_REFRESH, PRE_TARGET_REQUEST_CODE)
        );
        Log.i(TAG, "Scheduled widget refresh alarm: 07:55 daily");
    }

    static void cancel(Context context) {
        Context appContext = context.getApplicationContext();
        JobScheduler jobScheduler = (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler != null) {
            jobScheduler.cancel(ONE_OFF_JOB_PRE_TARGET_ID);
            jobScheduler.cancel(ONE_OFF_JOB_VISIBLE_EDGE_ID);
            jobScheduler.cancel(ONE_OFF_JOB_DAILY_ID);
            jobScheduler.cancel(ONE_OFF_JOB_RETRY_ID);
            jobScheduler.cancel(ONE_OFF_JOB_MANUAL_ID);
            jobScheduler.cancel(ONE_OFF_JOB_FALLBACK_ID);
            jobScheduler.cancel(ONE_OFF_JOB_WEATHER_ID);
            jobScheduler.cancel(ONE_OFF_JOB_BOOT_ID);
            jobScheduler.cancel(ONE_OFF_JOB_LEGACY_ID);
        }
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(pendingIntent(appContext, ACTION_PRE_TARGET_REFRESH, PRE_TARGET_REQUEST_CODE));
        cancelObsoleteSchedules(appContext, alarmManager);
        cancelLegacyReceiverAlarms(alarmManager, appContext);
        Log.i(TAG, "Cancelled widget refresh alarms/jobs");
    }

    static void scheduleRetry(Context context, String reason) {
        Log.i(TAG, "Auto retry disabled; waiting for next 07:55 refresh. reason=" + reason);
    }

    static void cancelRetry(Context context) {
        Log.i(TAG, "Auto retry already disabled");
    }

    static void scheduleOneOffJob(Context context) {
        scheduleOneOffJob(context, "manual");
    }

    static void scheduleOneOffJob(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        JobScheduler jobScheduler = (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) {
            return;
        }
        PersistableBundle extras = new PersistableBundle();
        String safeReason = reason == null ? "manual" : reason;
        extras.putString("reason", safeReason);
        extras.putBoolean("morningPriority", isMorningPriorityReason(safeReason));
        JobInfo jobInfo = new JobInfo.Builder(
                jobIdForReason(safeReason),
                new ComponentName(appContext, RiskRefreshJobService.class)
        )
                .setMinimumLatency(ONE_OFF_JOB_DELAY_MS)
                .setOverrideDeadline(overrideDeadlineMillis(safeReason))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setExtras(extras)
                .setBackoffCriteria(30L * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        int result = jobScheduler.schedule(jobInfo);
        Log.i(TAG, "Scheduled one-off widget refresh job result=" + result + " reason=" + safeReason);
    }

    private static PendingIntent pendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, RiskWidgetProvider.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent legacyReceiverPendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, RiskUpdateReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void cancelLegacyReceiverAlarms(AlarmManager alarmManager, Context context) {
        alarmManager.cancel(legacyReceiverPendingIntent(context, ACTION_PRE_TARGET_REFRESH, PRE_TARGET_REQUEST_CODE));
        alarmManager.cancel(legacyReceiverPendingIntent(context, ACTION_VISIBLE_EDGE_REFRESH, VISIBLE_EDGE_REQUEST_CODE));
        alarmManager.cancel(legacyReceiverPendingIntent(context, ACTION_DAILY_REFRESH, DAILY_REQUEST_CODE));
    }

    private static void cancelObsoleteSchedules(Context context, AlarmManager alarmManager) {
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler != null) {
            jobScheduler.cancel(RiskRefreshJobService.PERIODIC_JOB_ID);
            jobScheduler.cancel(ONE_OFF_JOB_VISIBLE_EDGE_ID);
            jobScheduler.cancel(ONE_OFF_JOB_DAILY_ID);
            jobScheduler.cancel(ONE_OFF_JOB_RETRY_ID);
            jobScheduler.cancel(ONE_OFF_JOB_FALLBACK_ID);
        }
        alarmManager.cancel(pendingIntent(context, ACTION_VISIBLE_EDGE_REFRESH, VISIBLE_EDGE_REQUEST_CODE));
        alarmManager.cancel(pendingIntent(context, ACTION_DAILY_REFRESH, DAILY_REQUEST_CODE));
        alarmManager.cancel(pendingIntent(context, "com.codexrisk.widget.FALLBACK_REFRESH", FALLBACK_REQUEST_CODE));
        alarmManager.cancel(pendingIntent(context, "com.codexrisk.widget.RETRY_REFRESH", RETRY_REQUEST_CODE));
        alarmManager.cancel(pendingIntent(context, ACTION_DAILY_REFRESH, TEST_DAILY_REQUEST_CODE));
        alarmManager.cancel(legacyReceiverPendingIntent(context, "com.codexrisk.widget.FALLBACK_REFRESH", FALLBACK_REQUEST_CODE));
        alarmManager.cancel(legacyReceiverPendingIntent(context, "com.codexrisk.widget.RETRY_REFRESH", RETRY_REQUEST_CODE));
        alarmManager.cancel(legacyReceiverPendingIntent(context, ACTION_DAILY_REFRESH, TEST_DAILY_REQUEST_CODE));
    }

    private static void scheduleAlarm(AlarmManager alarmManager, int type, long triggerAtMillis, PendingIntent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(type, triggerAtMillis, intent);
                return;
            }
            alarmManager.setExactAndAllowWhileIdle(type, triggerAtMillis, intent);
            return;
        }
        alarmManager.setExact(type, triggerAtMillis, intent);
    }

    private static long overrideDeadlineMillis(String reason) {
        return isMorningPriorityReason(reason) ? 30L * 1000L : 5L * 60L * 1000L;
    }

    private static boolean isMorningPriorityReason(String reason) {
        if (reason == null) {
            return false;
        }
        return ACTION_PRE_TARGET_REFRESH.equals(reason)
                || ACTION_VISIBLE_EDGE_REFRESH.equals(reason)
                || ACTION_DAILY_REFRESH.equals(reason)
                || reason.contains("BOOT_COMPLETED")
                || reason.contains("MY_PACKAGE_REPLACED")
                || reason.contains("SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED");
    }

    private static int jobIdForReason(String reason) {
        if (ACTION_PRE_TARGET_REFRESH.equals(reason)) {
            return ONE_OFF_JOB_PRE_TARGET_ID;
        }
        if (reason != null && reason.startsWith("weather-after-")) {
            return ONE_OFF_JOB_WEATHER_ID;
        }
        if (reason != null && reason.startsWith("legacy-")) {
            return ONE_OFF_JOB_LEGACY_ID;
        }
        if (reason != null && (
                reason.contains("BOOT_COMPLETED")
                        || reason.contains("MY_PACKAGE_REPLACED")
                        || reason.contains("SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        )) {
            return ONE_OFF_JOB_BOOT_ID;
        }
        return ONE_OFF_JOB_MANUAL_ID;
    }

    private static long nextFixedTriggerMillis(int hour, int minute) {
        Calendar calendar = WidgetClock.chinaCalendar();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        if (calendar.getTimeInMillis() <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }
}
