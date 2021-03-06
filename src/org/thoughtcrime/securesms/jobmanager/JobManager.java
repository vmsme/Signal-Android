package org.thoughtcrime.securesms.jobmanager;

import android.support.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class JobManager {

  private static final Constraints NETWORK_CONSTRAINT = new Constraints.Builder()
                                                                       .setRequiredNetworkType(NetworkType.CONNECTED)
                                                                       .build();

  private final Executor executor = Executors.newSingleThreadExecutor();

  private final WorkManager workManager;

  public JobManager(@NonNull WorkManager workManager) {
    this.workManager  = workManager;
  }

  public void add(Job job) {
    executor.execute(() -> {
      workManager.synchronous().pruneWorkSync();

      JobParameters jobParameters = job.getJobParameters();

      if (jobParameters == null) {
        throw new IllegalStateException("Jobs must have JobParameters at this stage. (" + job.getClass().getSimpleName() + ")");
      }

      Data.Builder dataBuilder = new Data.Builder().putInt(Job.KEY_RETRY_COUNT, jobParameters.getRetryCount())
                                                   .putLong(Job.KEY_RETRY_UNTIL, jobParameters.getRetryUntil())
                                                   .putLong(Job.KEY_SUBMIT_TIME, System.currentTimeMillis())
                                                   .putBoolean(Job.KEY_REQUIRES_NETWORK, jobParameters.requiresNetwork())
                                                   .putBoolean(Job.KEY_REQUIRES_MASTER_SECRET, jobParameters.requiresMasterSecret())
                                                   .putBoolean(Job.KEY_REQUIRES_SQLCIPHER, jobParameters.requiresSqlCipher());
      Data data = job.serialize(dataBuilder);

      OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest.Builder(job.getClass())
                                                                        .setInputData(data)
                                                                        .setBackoffCriteria(BackoffPolicy.LINEAR, OneTimeWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS);

      if (jobParameters.requiresNetwork()) {
        requestBuilder.setConstraints(NETWORK_CONSTRAINT);
      }

      OneTimeWorkRequest request = requestBuilder.build();

      job.onSubmit(request.getId());

      String groupId = jobParameters.getGroupId();
      if (groupId != null) {
        ExistingWorkPolicy policy = jobParameters.shouldIgnoreDuplicates() ? ExistingWorkPolicy.KEEP : ExistingWorkPolicy.APPEND;
        workManager.beginUniqueWork(groupId, policy, request).enqueue();
      } else {
        workManager.beginWith(request).enqueue();
      }
    });
  }
}
