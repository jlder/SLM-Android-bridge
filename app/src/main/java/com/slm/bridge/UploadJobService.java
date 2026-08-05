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

/** Resumes durable Drive uploads after the foreground recorder UI has closed. */
public final class UploadJobService extends JobService {
    private static final int JOB_ID = 0x534C4D;
    private ExecutorService executor;

    static void schedule(Context context) {
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, UploadJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    static void cancel(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        IntegrityDiagnostics.initialize(this);
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> runUploads(parameters));
        return true;
    }

    private void runUploads(JobParameters parameters) {
        boolean retry = false;
        try {
            TransferStore store = new TransferStore(this);
            if (!store.hasPendingUploads()) {
                jobFinished(parameters, false);
                return;
            }
            DriveCredentials credentials = new DriveCredentialStore(this).load();
            Network network = parameters.getNetwork();
            if (credentials == null || network == null) {
                jobFinished(parameters, credentials != null);
                return;
            }
            GoogleDriveUploader uploader = new GoogleDriveUploader(() -> network, store);
            for (TransferStore.Item item : store.pendingUploads()) {
                if (Thread.currentThread().isInterrupted()) {
                    retry = true;
                    break;
                }
                try {
                    uploader.upload(item, credentials, percent -> {});
                    if (!item.archiveRequested) store.delete(item.id);
                } catch (Exception e) {
                    retry = true;
                    break;
                }
            }
            if (!store.hasPendingUploads()) {
                new DriveCredentialStore(this).clear();
            } else {
                retry = true;
            }
        } catch (Exception e) {
            retry = true;
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
}
