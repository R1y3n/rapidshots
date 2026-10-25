package com.example.rapidshot.ui;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.rapidshot.R;
import com.example.rapidshot.camera.BurstCaptureEngine;
import com.example.rapidshot.camera.CameraSetupHelper;
import com.example.rapidshot.camera.VolumeButtonReceiver;
import com.example.rapidshot.save.SaveManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements BurstCaptureEngine.EngineCallback,
                   SaveManager.SaveCallback,
                   VolumeButtonReceiver.VolumeCallback {

    private static final String TAG = "RapidShot/Main";
    private static final int REQ_PERMISSIONS = 1;

    // ── Views ──────────────────────────────────────────────────────────────
    private TextureView mTextureView;
    private View        mShutterBtn;
    private View        mShutterRing;
    private TextView    mShotCounter;
    private TextView    mSaveCounter;
    private TextView    mStatusText;
    private TextView    mSpecsText;
    private TextView    mQueueText;
    private ImageView   mThumbView;
    private View        mBurstIndicator;
    private View        mHoldHint;

    // ── Core ───────────────────────────────────────────────────────────────
    private BurstCaptureEngine mEngine;
    private SaveManager        mSaveManager;
    private VolumeButtonReceiver mVolumeReceiver;
    private CameraSetupHelper.CameraInfo mCameraInfo;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private boolean mCameraReady = false;
    private boolean mBurstActive = false;

    // ── Required Permissions ───────────────────────────────────────────────
    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return perms.toArray(new String[0]);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bindViews();

        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
        mVolumeReceiver = new VolumeButtonReceiver(this);

        if (hasPermissions()) {
            initCameraWhenReady();
        } else {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQ_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermissions() && mEngine == null && mTextureView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mBurstActive) stopBurst();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
    }

    // ── Key dispatch — volume button hijack ───────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mCameraReady && mVolumeReceiver.onKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // ── VolumeButtonReceiver callbacks ────────────────────────────────────

    @Override public void onSingleShot()  { takeSingleShot(); }
    @Override public void onBurstStart()  { startBurst(); }
    @Override public void onBurstStop()   { stopBurst(); }

    // ── Camera init ───────────────────────────────────────────────────────

    private void initCameraWhenReady() {
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceListener);
        }
    }

    private void openCamera() {
        mCameraInfo = CameraSetupHelper.getBestBackCamera(this);
        if (mCameraInfo == null) {
            setStatus("No back camera found");
            return;
        }

        setStatus("Opening " + mCameraInfo.megapixels + "MP sensor…");
        mSpecsText.setText(mCameraInfo.megapixels + "MP  ·  " + mCameraInfo.maxJpegSize.getWidth()
                + "×" + mCameraInfo.maxJpegSize.getHeight());

        SurfaceTexture st = mTextureView.getSurfaceTexture();
        if (st == null) { setStatus("Surface not ready"); return; }
        st.setDefaultBufferSize(1920, 1080);
        Surface surface = new Surface(st);

        mEngine = new BurstCaptureEngine(this, this);
        mSaveManager = new SaveManager(this, mEngine, this);
        mSaveManager.startDraining();

        mEngine.openCamera(mCameraInfo.cameraId, surface);
    }

    private void releaseCamera() {
        mCameraReady = false;
        if (mSaveManager != null) {
            mSaveManager.stopAndFlush();
            mSaveManager = null;
        }
        if (mEngine != null) {
            mEngine.release();
            mEngine = null;
        }
    }

    // ── Burst control ─────────────────────────────────────────────────────

    private void startBurst() {
        if (!mCameraReady || mBurstActive) return;
        mBurstActive = true;
        mEngine.startBurst();
        showBurstUI(true);
        animateShutterPulse(true);
    }

    private void stopBurst() {
        if (!mBurstActive) return;
        mBurstActive = false;
        mEngine.stopBurst();
        showBurstUI(false);
        animateShutterPulse(false);
    }

    private void takeSingleShot() {
        if (!mCameraReady || mBurstActive) return;
        mEngine.startBurst();
        mUiHandler.postDelayed(() -> {
            if (mEngine != null) mEngine.stopBurst();
        }, 80);
    }

    // ── BurstCaptureEngine.EngineCallback ─────────────────────────────────

    @Override
    public void onReady(Size stillSize) {
        mCameraReady = true;
        mUiHandler.post(() -> {
            setStatus("Ready  ·  hold VOL ▼ or shutter to burst");
            mHoldHint.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onBurstStarted() {
        mUiHandler.post(() -> setStatus("● BURST ACTIVE"));
    }

    @Override
    public void onShotFired(int count) {
        mUiHandler.post(() -> mShotCounter.setText(String.valueOf(count)));
    }

    @Override
    public void onFrameQueued(int count, int queueDepth) {
        mUiHandler.post(() -> mQueueText.setText("queue: " + queueDepth));
    }

    @Override
    public void onBurstStopped(int fired, int saved) {
        mUiHandler.post(() -> {
            setStatus("Saving " + fired + " shots…");
            mBurstIndicator.setVisibility(View.GONE);
        });
    }

    @Override
    public void onError(String reason) {
        mUiHandler.post(() -> {
            setStatus("Error: " + reason);
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        });
    }

    // ── SaveManager.SaveCallback ───────────────────────────────────────────

    @Override
    public void onShotSaved(int totalSaved, Uri uri, byte[] coverThumbJpeg) {
        mUiHandler.post(() -> {
            mSaveCounter.setText("saved: " + totalSaved);
            if (coverThumbJpeg != null) {
                Bitmap bmp = BitmapFactory.decodeByteArray(coverThumbJpeg, 0, coverThumbJpeg.length);
                if (bmp != null) mThumbView.setImageBitmap(bmp);
            }
            int pending = mEngine != null ? mEngine.pendingSaveCount() : 0;
            if (pending == 0 && !mBurstActive) {
                setStatus("Done — " + totalSaved + " shots saved");
            }
        });
    }

    @Override
    public void onSaveFailed(int index, String reason) {
        Log.e(TAG, "Save #" + index + " failed: " + reason);
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private void bindViews() {
        mTextureView   = findViewById(R.id.texture_view);
        mShutterBtn    = findViewById(R.id.shutter_btn);
        mShutterRing   = findViewById(R.id.shutter_ring);
        mShotCounter   = findViewById(R.id.shot_counter);
        mSaveCounter   = findViewById(R.id.save_counter);
        mStatusText    = findViewById(R.id.status_text);
        mSpecsText     = findViewById(R.id.specs_text);
        mQueueText     = findViewById(R.id.queue_text);
        mThumbView     = findViewById(R.id.thumb_view);
        mBurstIndicator = findViewById(R.id.burst_indicator);
        mHoldHint       = findViewById(R.id.hold_hint);

        mShutterBtn.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    mUiHandler.postDelayed(mShutterLongPress,
                            VolumeButtonReceiver.LONG_PRESS_THRESHOLD_MS);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    mUiHandler.removeCallbacks(mShutterLongPress);
                    if (mBurstActive) stopBurst();
                    else takeSingleShot();
                    return true;
            }
            return false;
        });
    }

    private final Runnable mShutterLongPress = this::startBurst;

    private void showBurstUI(boolean active) {
        mBurstIndicator.setVisibility(active ? View.VISIBLE : View.GONE);
        mHoldHint.setVisibility(active ? View.GONE : View.VISIBLE);
        if (!active) {
            mShotCounter.setText("0");
        }
    }

    private ObjectAnimator mPulseAnimator;
    private void animateShutterPulse(boolean start) {
        if (mPulseAnimator != null) { mPulseAnimator.cancel(); mPulseAnimator = null; }
        if (start) {
            mPulseAnimator = ObjectAnimator.ofFloat(mShutterRing, "alpha", 1f, 0.3f);
            mPulseAnimator.setDuration(300);
            mPulseAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            mPulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            mPulseAnimator.start();
        } else {
            mShutterRing.setAlpha(1f);
        }
    }

    private void setStatus(String msg) {
        mStatusText.setText(msg);
    }

    // ── TextureView surface listener ──────────────────────────────────────

    private final TextureView.SurfaceTextureListener mSurfaceListener =
            new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
            if (hasPermissions()) openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
            releaseCamera(); return true;
        }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
    };

    // ── Permissions ───────────────────────────────────────────────────────

    private boolean hasPermissions() {
        for (String p : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERMISSIONS) {
            if (hasPermissions()) initCameraWhenReady();
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
        }
    }
}
