package com.example.penguinz0player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PiPControlReceiver extends BroadcastReceiver {
    public static final String ACTION_TOGGLE_PLAYBACK = "com.example.penguinz0player.PIP_TOGGLE_PLAYBACK";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_TOGGLE_PLAYBACK.equals(intent.getAction())) {
            MainActivity.dispatchPipToggle();
        }
    }
}
