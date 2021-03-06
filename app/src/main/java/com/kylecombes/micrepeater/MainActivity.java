package com.kylecombes.micrepeater;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.analytics.FirebaseAnalytics;

import static java.lang.System.currentTimeMillis;

/**
 * Service that runs in background and records in one thread, then plays back in another thread.
 */
public class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getCanonicalName();
    FirebaseAnalytics mFirebaseAnalytics;

    private AudioManager audioManager;
    Intent audioRelayServiceIntent;
    private long startTime;

    boolean mScoAudioConnected = false;
    boolean recordingInProgress = false;
    static boolean firebaseAnalyticsOn = true;
    static int streamType = AudioManager.STREAM_VOICE_CALL;

    // View elements
    ImageView bluetoothIcon;
    TextView bluetoothStatusTV;
    Button startButton;
    Button stopButton;
    ImageButton settingButton;
    TextView versionNumber;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        requestNeededPermissions();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        bindingViews();
        if (firebaseAnalyticsOn) mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
        registerBluetoothSCOListener();
    }

    public void bindingViews() {
        // Find our view elements so we can change their properties later
        bluetoothIcon = findViewById(R.id.imageView_main_bluetooth);
        bluetoothStatusTV = findViewById(R.id.textView_main_bluetoothStatus);
        startButton = findViewById(R.id.button_main_start);
        stopButton = findViewById(R.id.button_main_stop);
        settingButton = findViewById(R.id.button_setting);
        versionNumber = findViewById(R.id.version);
        versionNumber.setText(getResources().getString(R.string.version, BuildConfig.VERSION_CODE));
    }

    public static void activateFirebase(boolean isChecked){
        firebaseAnalyticsOn = isChecked;
        Log.i(TAG, "Switched firebase analytics" + firebaseAnalyticsOn);
    }

    public void registerBluetoothSCOListener() {
        // Register a listener to respond to Bluetooth connect/disconnect events
        IntentFilter intent = new IntentFilter();
        intent.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        intent.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        intent.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intent.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        registerReceiver(BluetoothStateReceiver.getInstance(), intent);

        // Register a callback to get notified about Bluetooth connect/disconnect events
        BluetoothStateReceiver.getInstance().registerStateChangeReceiver(
                new BluetoothStateReceiver.StateChangeReceiver() {
                    public void stateChanged(boolean deviceConnected, boolean scoAudioConnected) {
                        if (deviceConnected && !scoAudioConnected)
                            activateBluetoothSco();
                        mScoAudioConnected = scoAudioConnected;
                        if (recordingInProgress && !scoAudioConnected) {
                            stopRecording();
                        }
                        updateViewStates();
                    }
                }
        );
    }

    public void onSettingButtonPressed(View v) {
        Intent intent = new Intent(MainActivity.this, SettingActivity.class);
        startActivity(intent);

    }

    public void onInfoButtonPressed(View v){
        Intent intent = new Intent(MainActivity.this, InformationActivity.class);
        startActivity(intent);
    }

    public void onStartButtonPressed(View v) {
        startAudioService();
        updateViewStates();

        // Log this start button press in Firebase
        Bundle bundle = new Bundle();
        bundle.putString("RelayingControlAction", "start");
        if (firebaseAnalyticsOn) mFirebaseAnalytics.logEvent("RelayingButtonPress", bundle);
        startTime = currentTimeMillis();
    }

    public void onStopButtonPressed(View v) {
        stopRecording();

        // Log this stop button press in Firebase
        long elapsedTimeS = (currentTimeMillis() - startTime) / 1000;
        Bundle bundle = new Bundle();
        bundle.putString("RelayingControlAction", "stop");
        bundle.putInt("ElapsedSeconds", (int) elapsedTimeS);
        if (firebaseAnalyticsOn) mFirebaseAnalytics.logEvent("RelayingButtonPress", bundle);
    }


    @Override
    protected void onStart() {
        super.onStart();
        activateBluetoothSco();
    }

    @Override
    protected void onResume() {
        super.onResume();

        AudioRelayService ars = AudioRelayService.getInstance();
        if (ars != null) {
            recordingInProgress = ars.recordingInProgress();
        } else {
            recordingInProgress = false;
        }
        updateViewStates();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!recordingInProgress && !isChangingConfigurations()) {
            audioManager.stopBluetoothSco();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!recordingInProgress) {
            unregisterReceiver(BluetoothStateReceiver.getInstance());
        }
    }

    /**
     * Checks to make sure the app has the needed permissions:
     * - RECORD_AUDIO
     * If those permissions have not been granted, they will be requested.
     */
    private void requestNeededPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    1234);
        }

    }

    /**
     * Attempts to start Bluetooth SCO (used for streaming audio to and from Bluetooth devices).
     */
    private void activateBluetoothSco() {
        if (audioManager == null || !audioManager.isBluetoothScoAvailableOffCall()) {
            Log.e(TAG, "SCO ist not available. Recording is not possible");
            mScoAudioConnected = false;
            return;
        }

        if (!audioManager.isBluetoothScoOn()) {
            audioManager.startBluetoothSco();
        }
    }

    /**
     * Starts the AudioRelayService.
     */
    private void startAudioService() {
        audioRelayServiceIntent = new Intent(this, AudioRelayService.class);
        audioRelayServiceIntent.putExtra(AudioRelayService.STREAM_KEY, streamType);
        // Set default volume control
        setVolumeControlStream(streamType);
        startService(audioRelayServiceIntent);
        recordingInProgress = true;
    }

    /**
     * Stops the AudioRelayService.
     */
    private void stopRecording() {
        AudioRelayService ars = AudioRelayService.getInstance();
        if (ars != null) {
            ars.shutDown();
        }
        recordingInProgress = false;

        updateViewStates();
    }

    /**
     * Refreshes the appearance (e.g. text, enabled/disabled) of Buttons, TextViews, etc according
     * to the current state of the application.
     */
    private void updateViewStates() {
        if (recordingInProgress) {
            //Display recording icon and change text to red
            bluetoothIcon.setImageDrawable(getResources().getDrawable(R.drawable.speaker));
            bluetoothStatusTV.setText(R.string.broadcasting_in_process);
            bluetoothStatusTV.setTextColor(getResources().getColor(R.color.red));
        } else if (mScoAudioConnected) {
            // Display Bluetooth icon at full opacity
            bluetoothIcon.setImageDrawable(getResources().getDrawable(R.drawable.bluetooth));
            bluetoothIcon.setImageAlpha(255);
            bluetoothStatusTV.setText(R.string.bluetooth_available);
            bluetoothStatusTV.setTextColor(getResources().getColor(R.color.blue));
        } else {
            // Make Bluetooth icon 50% transparent
            bluetoothIcon.setImageDrawable(getResources().getDrawable(R.drawable.bluetooth));
            bluetoothIcon.setImageAlpha(128);
            bluetoothStatusTV.setText(R.string.bluetooth_unavailable);
            bluetoothStatusTV.setTextColor(getResources().getColor(R.color.blue));
        }
        startButton.setEnabled(mScoAudioConnected && !recordingInProgress);
        stopButton.setEnabled(mScoAudioConnected && recordingInProgress);
    }

    /**
     * Check if wired speakers are connected (AUX line, wired headphones/headset)
     * @return: true if wired speakers are connected
     */
    private boolean isWiredHeadsetOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo output : outputs) {
                if (output.getType() == AudioDeviceInfo.TYPE_AUX_LINE ||
                        output.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        output.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET)
                    return true;
            }
            return false;
        } else {
            return audioManager.isWiredHeadsetOn();
        }
    }

}
