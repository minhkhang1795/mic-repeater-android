package com.pint.micrepeater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioRelayService extends Service {

    private static AudioRelayService mInstance;

    private static final String TAG = AudioRelayService.class.getCanonicalName();

    public static final String STREAM_KEY = "STREAM";

    private static final String NOTIFICATION_CHANNEL_ID = BuildConfig.class.getPackage().toString()
            + "." + TAG;
    private static final String NOTIFICATION_MESSAGE = "Mic Repeater is running.";

    private static final int SAMPLING_RATE_IN_HZ = getMinSupportedSampleRate();

    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * Size of the buffer where the audio data is stored by Android
     */
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLING_RATE_IN_HZ,
            CHANNEL_CONFIG, AUDIO_FORMAT);

    /**
     * Signals whether a recording is in progress (true) or not (false).
     */
    private final AtomicBoolean recordingInProgress = new AtomicBoolean(false);

    private AudioRecord recorder = null;

    private Thread recordingThread = null;

    private int streamOutput;

    public static AudioRelayService getInstance() {
        return mInstance;
    }

    @Override
    public void onCreate() {
        Log.d("AudioRelayService", "Sampling rate: " + SAMPLING_RATE_IN_HZ + " Hz");
        mInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        streamOutput = intent.getIntExtra(STREAM_KEY, AudioManager.STREAM_ALARM);

        displayNotification();

        startRecording();

        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void startRecording() {
        // Depending on the device one might has to change the AudioSource, e.g. to DEFAULT
        // or VOICE_COMMUNICATION
        recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                SAMPLING_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);

        improveRecorder(recorder);
        recorder.startRecording();

        recordingInProgress.set(true);

        recordingThread = new Thread(new RecordingRunnable(), "Recording Thread");
        recordingThread.start();

    }

    private void improveRecorder(AudioRecord recorder) {
        int audioSessionId = recorder.getAudioSessionId();

        // Turn on Android library filter for reducing background noise in recordings
        if (NoiseSuppressor.isAvailable()) {
              NoiseSuppressor.create(audioSessionId);
        }

        // Android library filter for automatic volume control in recordings
//        if(AutomaticGainControl.isAvailable()) {
//             AutomaticGainControl.create(audioSessionId);
//        }

        // Android library filter for reducing echo in recordings
        if (AcousticEchoCanceler.isAvailable()) {
             AcousticEchoCanceler.create(audioSessionId);
        }
    }

    public void stopRecording() {
        if (null == recorder) {
            return;
        }
        recordingInProgress.set(false);
        recorder.stop();
        recorder.release();
        recorder = null;
        recordingThread = null;
    }

    @Override
    public void onDestroy() {
        stopRecording();
    }

    public void shutDown() {
        stopRecording();
        mInstance = null;
        stopSelf();
    }

    private void displayNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    "AudioRelayService", NotificationManager.IMPORTANCE_NONE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            assert manager != null;
            manager.createNotificationChannel(chan);

            Notification.Builder notificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
            Notification notification = notificationBuilder.setOngoing(true)
                    .setContentTitle(NOTIFICATION_MESSAGE)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();
            startForeground(2, notification);
        } else {
            Notification.Builder notification = new Notification.Builder(this)
                    .setContentTitle(NOTIFICATION_MESSAGE);
            startForeground(1, notification.build());
        }
    }

    private class RecordingRunnable implements Runnable {

        @Override
        public void run() {

            AudioTrack audio = new AudioTrack(streamOutput,
                    SAMPLING_RATE_IN_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    BUFFER_SIZE,
                    AudioTrack.MODE_STREAM);

            final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            audio.play();

            while (recordingInProgress.get()) {
                int result = recorder.read(buffer, BUFFER_SIZE);
                if (result < 0) {
                    Log.w(TAG, "Reading of buffer failed.");
                } else {
                    audio.write(buffer.array(), 0, BUFFER_SIZE);
                    buffer.clear();
                }
            }
        }
    }

    public boolean recordingInProgress() {
        return recordingInProgress.get();
    }

    private static int getMinSupportedSampleRate() {
        final int[] validSampleRates = new int[] { 8000, 11025, 16000, 22050,
                32000, 37800, 44056, 44100, 47250, 48000, 50000, 50400, 88200,
                96000, 176400, 192000, 352800, 2822400, 5644800 };
        /*
         * Selecting default audio input source for recording since
         * AudioFormat.CHANNEL_CONFIGURATION_DEFAULT is deprecated and selecting
         * default encoding format.
         */
        for (int validSampleRate : validSampleRates) {
            int result = AudioRecord.getMinBufferSize(validSampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT);
            if (result > 0) {
                // return the minimum supported audio sample rate
                return validSampleRate;
            }
        }
        // If none of the sample rates are supported return -1 and handle it in calling method
        return -1;
    }
}
