package com.example.rapidshot.camera;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.util.Log;

/**
 * VolumeButtonReceiver
 *
 * Replicates the factory app's volume-button burst trigger:
 *
 * From the decompiled app:
 *   KEY_VOLUME_KEY_FUNCTION → "camera_volume_key_function_setting_summary"
 *   Values: volume / shutter / zoom (pref_volume_key_function_key)
 *   Default: SHUTTER (camera_volume_key_function_shutter)
 *
 *   Flow:
 *     onKeyDown(KEYCODE_VOLUME_DOWN) → setIsVolumeDown(true)
 *       → if not long-press yet: post LONG_PRESS_TIMEOUT runnable
 *     onKeyDown fires continuously while held (dispatchKeyEvent repeat)
 *       → after first repeat: onBurstShotStart() → trySendRepeatBurstCaptureMessage
 *     onKeyUp(KEYCODE_VOLUME_DOWN) → setIsVolumeDown(false) → onBurstShotEnd()
 *
 * IMPORTANT: To suppress the system volume UI, the Activity must hold
 * setVolumeControlStream(AudioManager.STREAM_MUSIC) and override
 * dispatchKeyEvent. No special permission is required for this.
 */
public class VolumeButtonReceiver {

    private static final String TAG = "RapidShot/Volume";

    // Mirrors getLongPressTimeout() in the decompiled app — 500ms before
    // first repeat triggers burst vs single shot.
    public static final long LONG_PRESS_THRESHOLD_MS = 500;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mKeyDownTime = 0;
    private boolean mIsVolumeDown = false;
    private boolean mBurstTriggered = false;

    private final Runnable mLongPressRunnable;
    private VolumeCallback mCallback;

    public VolumeButtonReceiver(VolumeCallback callback) {
        mCallback = callback;
        mLongPressRunnable = () -> {
            if (mIsVolumeDown) {
                Log.i(TAG, "Long press → burst start");
                mBurstTriggered = true;
                if (mCallback != null) mCallback.onBurstStart();
            }
        };
    }

    /**
     * Call from Activity.dispatchKeyEvent(event).
     * Return true if consumed (suppresses system volume change).
     */
    public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();

        // Only handle volume down; let volume up pass through normally
        // (unless we're in a burst, where we use it as stop signal)
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN &&
            code != KeyEvent.KEYCODE_VOLUME_UP) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (!mIsVolumeDown) {
                    // Fresh press
                    mIsVolumeDown    = true;
                    mBurstTriggered  = false;
                    mKeyDownTime     = System.currentTimeMillis();
                    // Schedule long-press burst trigger
                    mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_THRESHOLD_MS);
                    Log.d(TAG, "Volume DOWN pressed");
                }
                // event.getRepeatCount() > 0 means key auto-repeat (held)
                // Factory app uses this to confirm burst is still wanted
                return true; // consume, suppress volume HUD
            }
            if (code == KeyEvent.KEYCODE_VOLUME_UP && mBurstTriggered) {
                // Volume-up while bursting = stop (factory app alternative)
                triggerStop();
                return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                mHandler.removeCallbacks(mLongPressRunnable);
                long held = System.currentTimeMillis() - mKeyDownTime;
                Log.d(TAG, "Volume UP after " + held + "ms, burst=" + mBurstTriggered);

                if (mBurstTriggered) {
                    triggerStop();
                } else {
                    // Short press → single shot
                    if (mCallback != null) mCallback.onSingleShot();
                }
                mIsVolumeDown   = false;
                mBurstTriggered = false;
                return true;
            }
        }

        return false;
    }

    private void triggerStop() {
        mBurstTriggered = false;
        if (mCallback != null) mCallback.onBurstStop();
    }

    public boolean isBurstActive() { return mBurstTriggered; }

    public interface VolumeCallback {
        void onSingleShot();
        void onBurstStart();
        void onBurstStop();
    }
}
