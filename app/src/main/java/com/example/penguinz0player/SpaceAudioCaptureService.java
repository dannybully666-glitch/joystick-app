package com.example.penguinz0player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures eligible media playback from this app, processes it in PCM, then plays
 * the processed signal on a non-capturable sonification usage to avoid feedback.
 * The original YouTube track remains audible as the dry signal. This service
 * plays a wet-only reverb/echo return on a non-captured usage, avoiding both
 * duplicate dry playback and a feedback loop.
 */
public final class SpaceAudioCaptureService extends Service {
    private static final String TAG = "P0SpaceAudio";
    public static final String ACTION_START = "com.example.penguinz0player.spaceaudio.START";
    public static final String ACTION_STOP = "com.example.penguinz0player.spaceaudio.STOP";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String CHANNEL_ID = "p0_space_audio";
    private static final int NOTIFICATION_ID = 7318;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAMES_PER_READ = 1024;
    private static volatile SpaceAudioCaptureService instance;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean outputRequested = false;
    private volatile boolean outputActive = false;
    private volatile boolean userStopRequested = false;
    private MediaProjection projection;
    private AudioRecord recorder;
    private AudioTrack track;
    private Thread captureThread;
    private long captureStartedAt;

    public static void activateOutput() {
        SpaceAudioCaptureService current = instance;
        if (current != null) current.outputRequested = true;
    }

    public static boolean requestStop() {
        SpaceAudioCaptureService current = instance;
        if (current == null) return false;
        current.userStopRequested = true;
        current.running.set(false);
        AudioRecord input = current.recorder;
        if (input != null) {
            try { if (input.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) input.stop(); }
            catch (Throwable ignored) {}
        }
        if (current.captureThread == null) {
            current.stopForeground(true);
            current.stopSelf();
            MainActivity.dispatchSpaceAudioStatus("stopped", "Space audio processing stopped.");
        }
        return true;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running.get()) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            MainActivity.dispatchSpaceAudioStatus("error", "Android did not return audio-capture permission.");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("MediaProjection was not granted.");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.i(TAG, "MediaProjection stopped by Android or the user");
                    if (running.get()) {
                        MainActivity.dispatchSpaceAudioStatus("error", "Android stopped audio capture.");
                        // Preserve the error state; do not let the user-stop callback
                        // overwrite it with a generic OFF message during cleanup.
                        userStopRequested = false;
                        running.set(false);
                        AudioRecord activeRecord = recorder;
                        if (activeRecord != null) {
                            try { activeRecord.stop(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }, new android.os.Handler(getMainLooper()));
            running.set(true);
            outputRequested = false;
            outputActive = false;
            userStopRequested = false;
            captureStartedAt = SystemClock.elapsedRealtime();
            MainActivity.dispatchSpaceAudioStatus("listening", "Listening for eligible video audio…");
            captureThread = new Thread(this::captureLoop, "P0-Space-Audio-Capture");
            captureThread.start();
        } catch (Throwable error) {
            Log.e(TAG, "Unable to start playback capture", error);
            MainActivity.dispatchSpaceAudioStatus("error", friendlyError(error));
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void captureLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); }
        catch (Throwable ignored) {}
        AudioRecord localRecord = null;
        AudioTrack localTrack = null;
        try {
            AudioPlaybackCaptureConfigurationCompat config = makeCaptureConfiguration();
            AudioFormat inputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int minInput = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minInput <= 0) throw new IllegalStateException("This device cannot create a 48 kHz stereo capture stream.");
            localRecord = new AudioRecord.Builder()
                    .setAudioFormat(inputFormat)
                    // Leave enough headroom while the four-block pre-roll is queued
                    // into AudioTrack at activation, so AudioRecord does not overrun.
                    .setBufferSizeInBytes(Math.max(minInput * 8, FRAMES_PER_READ * CHANNELS * 2 * 8))
                    .setAudioPlaybackCaptureConfig(config.config)
                    .build();
            if (localRecord.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Android could not initialize the playback capture stream.");

            AudioAttributes outputAttributes = new AudioAttributes.Builder()
                    // Deliberately not MEDIA/GAME/UNKNOWN: do not recapture our wet return.
                    // Navigation guidance follows the media playback route and volume.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build();
            AudioFormat outputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            int minOutput = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minOutput <= 0) throw new IllegalStateException("This device cannot create a 48 kHz stereo output stream.");
            localTrack = new AudioTrack.Builder()
                    .setAudioAttributes(outputAttributes)
                    .setAudioFormat(outputFormat)
                    .setBufferSizeInBytes(Math.max(minOutput * 2, FRAMES_PER_READ * CHANNELS * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (localTrack.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("Android could not initialize the processed audio output.");

            recorder = localRecord;
            track = localTrack;
            if (!running.get()) return;
            localRecord.startRecording();
            if (localRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Android did not start the playback capture stream.");

            short[] input = new short[FRAMES_PER_READ * CHANNELS];
            short[] processed = new short[FRAMES_PER_READ * CHANNELS];
            ArrayDeque<short[]> prebuffer = new ArrayDeque<>();
            SpaceDsp dsp = new SpaceDsp(SAMPLE_RATE);
            int aboveThreshold = 0;
            boolean readyReported = false;
            long deadline = captureStartedAt + 10000L;

            while (running.get()) {
                int count = localRecord.read(input, 0, input.length, AudioRecord.READ_BLOCKING);
                if (count < 0) throw new IllegalStateException("Playback capture read failed (" + count + ").");
                if (count == 0) continue;
                if ((count & 1) != 0) count--; // Stereo PCM must end on a complete frame.
                if (count <= 0) continue;
                // Keep only a short pre-roll while the wet return is waiting to start.
                // Once active, process the reusable AudioRecord buffer directly to avoid
                // allocating a new PCM array for every 21 ms audio block.
                if (!outputActive) {
                    short[] queuedBlock = Arrays.copyOf(input, count);
                    prebuffer.addLast(queuedBlock);
                    while (prebuffer.size() > 4) prebuffer.removeFirst();

                    if (!readyReported) {
                        if (hasAudibleSignal(input, count)) aboveThreshold++;
                        else aboveThreshold = Math.max(0, aboveThreshold - 1);
                        if (aboveThreshold >= 2) {
                            readyReported = true;
                            Log.i(TAG, "Eligible app media audio detected; ready to activate wet-return processing");
                            MainActivity.dispatchSpaceAudioStatus("ready", "Video audio detected. Activating spatial reverb…");
                        } else if (SystemClock.elapsedRealtime() > deadline) {
                            throw new IllegalStateException("No capturable video audio was detected. The player may block playback capture.");
                        }
                    }
                }

                // The current block is already in prebuffer when activation is requested,
                // so the wet output starts without dropping or duplicating a block.
                if (readyReported && outputRequested && !outputActive) {
                    localTrack.play();
                    outputActive = true;
                    while (!prebuffer.isEmpty() && running.get()) {
                        short[] queuedBlock = prebuffer.removeFirst();
                        writeProcessed(localTrack, dsp, queuedBlock, processed, queuedBlock.length);
                    }
                    MainActivity.dispatchSpaceAudioStatus("active", "SPACE FX active · reverb and stereo delay");
                    Log.i(TAG, "Custom PCM space processing active");
                } else if (outputActive && readyReported && running.get()) {
                    writeProcessed(localTrack, dsp, input, processed, count);
                }
            }
        } catch (Throwable error) {
            if (running.get()) {
                Log.e(TAG, "Space audio capture/processing failed", error);
                MainActivity.dispatchSpaceAudioStatus("error", friendlyError(error));
            }
        } finally {
            running.set(false);
            outputActive = false;
            releaseAudio(localRecord, localTrack);
            if (recorder == localRecord) recorder = null;
            if (track == localTrack) track = null;
            if (projection != null) {
                try { projection.stop(); } catch (Throwable ignored) {}
                projection = null;
            }
            stopForeground(true);
            if (userStopRequested) MainActivity.dispatchSpaceAudioStatus("stopped", "Space audio processing stopped.");
            stopSelf();
        }
    }

    private AudioPlaybackCaptureConfigurationCompat makeCaptureConfiguration() {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Playback capture requires Android 10 or newer.");
        android.media.AudioPlaybackCaptureConfiguration config =
                new android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUid(Process.myUid())
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        return new AudioPlaybackCaptureConfigurationCompat(config);
    }

    private static boolean hasAudibleSignal(short[] samples, int count) {
        long sumSquares = 0;
        int peak = 0;
        for (int i = 0; i < count; i++) {
            int value = samples[i];
            int abs = Math.abs(value);
            if (abs > peak) peak = abs;
            sumSquares += (long) value * value;
        }
        double rms = Math.sqrt(sumSquares / (double) Math.max(1, count));
        return rms > 38.0 && peak > 130;
    }

    private static void writeProcessed(AudioTrack output, SpaceDsp dsp, short[] input,
                                       short[] processed, int length) {
        int count = length < 0 ? input.length : length;
        dsp.process(input, processed, count);
        int offset = 0;
        while (offset < count) {
            int written = output.write(processed, offset, count - offset, AudioTrack.WRITE_BLOCKING);
            if (written < 0) throw new IllegalStateException("Processed audio output failed (" + written + ").");
            if (written == 0) break;
            offset += written;
        }
    }

    private static String friendlyError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Android could not start space audio on this device.";
        return message;
    }

    private void releaseAudio(AudioRecord input, AudioTrack output) {
        if (input != null) {
            try { if (input.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) input.stop(); } catch (Throwable ignored) {}
            try { input.release(); } catch (Throwable ignored) {}
        }
        if (output != null) {
            try { if (output.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) output.pause(); } catch (Throwable ignored) {}
            try { output.flush(); } catch (Throwable ignored) {}
            try { output.release(); } catch (Throwable ignored) {}
        }
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, SpaceAudioCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 7319, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Space audio processing")
                .setContentText("Processing this app's video audio")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "Stop FX", stopPending)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Space audio processing", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public void onDestroy() {
        running.set(false);
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) {}
        }
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class AudioPlaybackCaptureConfigurationCompat {
        final android.media.AudioPlaybackCaptureConfiguration config;
        AudioPlaybackCaptureConfigurationCompat(android.media.AudioPlaybackCaptureConfiguration config) {
            this.config = config;
        }
    }

    /** Low-CPU stereo Schroeder reverb with a cross-channel echo tail and soft limiting. */
    private static final class SpaceDsp {
        private final float[][] combL;
        private final float[][] combR;
        private final int[] combPosL;
        private final int[] combPosR;
        private final float[] dampL = new float[4];
        private final float[] dampR = new float[4];
        private final float[] feedback = {0.86f, 0.85f, 0.84f, 0.83f};
        private final float damping = 0.19f;
        private final float[] echoL;
        private final float[] echoR;
        private final float[] diffuserL1 = new float[225];
        private final float[] diffuserR1 = new float[241];
        private final float[] diffuserL2 = new float[341];
        private final float[] diffuserR2 = new float[353];
        private int diffuserL1Pos, diffuserR1Pos, diffuserL2Pos, diffuserR2Pos;
        private int echoPosL;
        private int echoPosR;
        private float previousInL, previousInR, highPassL, highPassR;

        SpaceDsp(int sampleRate) {
            int[] delaysL = {1493, 1789, 1999, 2137};
            int[] delaysR = {1559, 1867, 2081, 2221};
            combL = new float[4][]; combR = new float[4][];
            combPosL = new int[4]; combPosR = new int[4];
            for (int i = 0; i < 4; i++) {
                combL[i] = new float[Math.max(32, delaysL[i] * sampleRate / 48000)];
                combR[i] = new float[Math.max(32, delaysR[i] * sampleRate / 48000)];
            }
            echoL = new float[Math.max(64, sampleRate * 29 / 100)];
            echoR = new float[Math.max(64, sampleRate * 41 / 100)];
        }

        void process(short[] input, short[] output, int length) {
            int frames = length / 2;
            for (int frame = 0, sample = 0; frame < frames; frame++, sample += 2) {
                float inL = input[sample] / 32768.0f;
                float inR = input[sample + 1] / 32768.0f;
                float hpL = inL - previousInL + 0.992f * highPassL;
                float hpR = inR - previousInR + 0.992f * highPassR;
                previousInL = inL; previousInR = inR; highPassL = hpL; highPassR = hpR;

                float sumL = 0f, sumR = 0f;
                for (int i = 0; i < 4; i++) {
                    float delayedL = combL[i][combPosL[i]];
                    float delayedR = combR[i][combPosR[i]];
                    dampL[i] += damping * (delayedL - dampL[i]);
                    dampR[i] += damping * (delayedR - dampR[i]);
                    combL[i][combPosL[i]] = hpL * 0.43f + dampL[i] * feedback[i];
                    combR[i][combPosR[i]] = hpR * 0.43f + dampR[i] * feedback[i];
                    combPosL[i]++; if (combPosL[i] >= combL[i].length) combPosL[i] = 0;
                    combPosR[i]++; if (combPosR[i] >= combR[i].length) combPosR[i] = 0;
                    sumL += delayedL; sumR += delayedR;
                }
                float reverbL = sumL * 0.25f;
                float reverbR = sumR * 0.25f;
                reverbL = allpass(reverbL, diffuserL1, diffuserL1Pos, 0.68f);
                diffuserL1Pos++; if (diffuserL1Pos >= diffuserL1.length) diffuserL1Pos = 0;
                reverbR = allpass(reverbR, diffuserR1, diffuserR1Pos, 0.68f);
                diffuserR1Pos++; if (diffuserR1Pos >= diffuserR1.length) diffuserR1Pos = 0;
                reverbL = allpass(reverbL, diffuserL2, diffuserL2Pos, 0.64f);
                diffuserL2Pos++; if (diffuserL2Pos >= diffuserL2.length) diffuserL2Pos = 0;
                reverbR = allpass(reverbR, diffuserR2, diffuserR2Pos, 0.64f);
                diffuserR2Pos++; if (diffuserR2Pos >= diffuserR2.length) diffuserR2Pos = 0;

                float echoTapL = echoL[echoPosL];
                float echoTapR = echoR[echoPosR];
                echoL[echoPosL] = hpL * 0.52f + echoTapR * 0.29f;
                echoR[echoPosR] = hpR * 0.52f + echoTapL * 0.29f;
                echoPosL++; if (echoPosL >= echoL.length) echoPosL = 0;
                echoPosR++; if (echoPosR >= echoR.length) echoPosR = 0;

                // Wet-only return: the YouTube iframe supplies the dry signal directly.
                // This stream adds a diffuse tail and cross-channel echo without doubling speech.
                float left = reverbL * 0.82f + reverbR * 0.18f + echoTapL * 0.46f + echoTapR * 0.12f;
                float right = reverbR * 0.82f + reverbL * 0.18f + echoTapR * 0.46f + echoTapL * 0.12f;
                output[sample] = toPcm(left);
                output[sample + 1] = toPcm(right);
            }
        }

        private static float allpass(float input, float[] buffer, int position, float gain) {
            float delayed = buffer[position];
            float output = delayed - gain * input;
            buffer[position] = input + gain * output;
            return output;
        }

        private static short toPcm(float value) {
            // Soft-knee limiter keeps reverberant peaks from clipping the handset DAC.
            float abs = Math.abs(value);
            if (abs > 0.88f) value = Math.signum(value) * (0.88f + (abs - 0.88f) * 0.20f);
            value = Math.max(-0.98f, Math.min(0.98f, value));
            return (short) (value * 32767.0f);
        }
    }
}
