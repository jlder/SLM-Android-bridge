package com.slm.bridge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Network;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Periodically refreshes the 30-day server validation receipt cache. */
public final class ServerValidationJobService extends JobService {
    private static final int JOB_ID = 0x534C52;
    private static final long REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private ExecutorService executor;

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, ServerValidationJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(REFRESH_INTERVAL_MS)
                .build();
        scheduler.schedule(job);
    }

    static void cancel(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        IntegrityDiagnostics.initialize(this);
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> refresh(parameters));
        return true;
    }

    private void refresh(JobParameters parameters) {
        boolean retry = false;
        try {
            DriveCredentials credentials = new DriveCredentialStore(this).load();
            Network network = parameters.getNetwork();
            if (credentials == null || network == null) {
                jobFinished(parameters, false);
                return;
            }
            TransferStore store = new TransferStore(this);
            ServerValidationCache cache = new ServerValidationCache(this);
            GoogleDriveUploader uploader = new GoogleDriveUploader(() -> network, store, cache);
            uploader.refreshValidationCache(credentials);
        } catch (Exception error) {
            retry = true;
            IntegrityDiagnostics.bridgeEvent("SYNC", "BACKGROUND_CACHE_REFRESH_FAILED",
                    "error=" + safe(error));
        }
        jobFinished(parameters, retry);
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (executor != null) executor.shutdownNow();
        return true;
    }

    @Override public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private static String safe(Exception error) {
        String value = error == null ? "unknown"
                : error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }
}
