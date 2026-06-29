package com.codexrisk.widget;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class RiskRefreshJobService extends JobService {
    private static final String TAG = "CodexRiskWidget";
    static final int PERIODIC_JOB_ID = 3101;
    private static final AtomicBoolean REFRESH_RUNNING = new AtomicBoolean(false);
    private volatile RefreshRun currentRun;
    private volatile Thread runningThread;

    @Override
    public boolean onStartJob(JobParameters params) {
        String reason = params == null ? "job" : params.getExtras().getString("reason", "job");
        if (!REFRESH_RUNNING.compareAndSet(false, true)) {
            Log.i(TAG, "Risk refresh job already running; skip duplicate trigger: " + reason);
            jobFinished(params, false);
            return false;
        }
        Log.i(TAG, "Risk refresh job scheduled start: " + reason);
        RefreshRun run = new RefreshRun(reason);
        currentRun = run;
        Thread thread = new Thread(() -> {
            try {
                RiskRefreshRunner.Result result = RiskRefreshRunner.refresh(this, reason, run::isCancelled);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (run.isCancelled()) {
                        Log.i(TAG, "Ignoring cancelled refresh job callback: " + run.reason);
                        return;
                    }
                    finishRun(run);
                    jobFinished(params, !result.success);
                    Log.i(TAG, "Risk refresh job finished callback: success=" + result.success);
                });
            } catch (RuntimeException error) {
                Log.e(TAG, "Risk refresh job crashed: " + reason, error);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (run.isCancelled()) {
                        Log.i(TAG, "Cancelled refresh job crashed after stop: " + run.reason);
                        return;
                    }
                    finishRun(run);
                    jobFinished(params, true);
                });
            }
        }, "CodexRiskRefreshJob");
        runningThread = thread;
        run.thread = thread;
        thread.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.w(TAG, "Risk refresh job stopped by system");
        RefreshRun run = currentRun;
        if (run != null) {
            run.cancelled.set(true);
            Thread thread = run.thread;
            if (thread != null) {
                thread.interrupt();
            }
        }
        finishRun(run);
        return true;
    }

    private void finishRun(RefreshRun run) {
        if (run == null || currentRun != run) {
            return;
        }
        runningThread = null;
        currentRun = null;
        REFRESH_RUNNING.set(false);
    }

    private static final class RefreshRun {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final String reason;
        volatile Thread thread;

        RefreshRun(String reason) {
            this.reason = reason;
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }
}
