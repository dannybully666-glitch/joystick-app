package com.example.penguinz0player;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class RemoteWebUpdateWorker extends Worker {
    public RemoteWebUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        RemoteWebUpdateManager.ensureBundledFallback(getApplicationContext());
        String status = RemoteWebUpdateManager.checkAndStage(getApplicationContext()).optString("status", "error");
        return "error".equals(status) ? Result.retry() : Result.success();
    }
}
