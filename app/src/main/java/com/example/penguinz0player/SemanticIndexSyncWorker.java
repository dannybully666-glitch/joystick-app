package com.example.penguinz0player;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class SemanticIndexSyncWorker extends Worker {
    public SemanticIndexSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        return SemanticDataSync.sync(getApplicationContext()) ? Result.success() : Result.retry();
    }
}
