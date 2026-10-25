package com.example.rapidshot.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class BurstCaptureEngine {

    private static final String TAG = "RapidShot/Engine";

    public static final int JPEG_QUALITY_BURST = 85;
    public static final int IMAGE_READER_MAX_IMAGES = 15;
    public static final int SAVE_QUEUE_MAX = 200;

    private static final long MAX_BURST_PIXELS = 12_500_000L;

    private final Context mContext;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewBuilder;
    private CaptureRequest.Builder mBurstBuilder;

    private ImageReader mImageReader;
    private Surface mPreviewSurface;

    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    private final Semaphore mCameraOpenLock = new Semaphore(1);
    private final AtomicBoolean mBurstActive = new AtomicBoolean(false);
    private final AtomicInteger mShotsFired   = new AtomicInteger(0);
    private final AtomicInteger mShotsSaved   = new AtomicInteger(0);

    public static class PendingShot {
        public final byte[] jpeg;
        public final long   timestampNs;
        public final int    sequenceIndex;
        PendingShot(byte[] j, long t, int idx) { jpeg=j; timestampNs=t; sequenceIndex=idx; }
    }

    private final ArrayBlockingQueue<PendingShot> mSaveQueue =
            new ArrayBlockingQueue<>(SAVE_QUEUE_MAX);

    private EngineCallback mCallback;
    private Size mStillSize;
    private String mCameraId;

    public BurstCaptureEngine(Context ctx, EngineCallback cb) {
        mContext  = ctx.getApplicationContext();
        mCallback = cb;
    }

    public void openCamera(String cameraId, Surface previewSurface) {
        mCameraId      = cameraId;
        mPreviewSurface = previewSurface;

        mCameraThread = new HandlerThread("CameraBackground");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        try {
            if (!mCameraOpenLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throw new RuntimeException("Camera open timed out");

            CameraManager manager =
                    (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

            mStillSize = pickBestBurstSize(manager, cameraId);
            Log.i(TAG, "Selected Burst Resolution: " + mStillSize);

            mImageReader = ImageReader.newInstance(
                    mStillSize.getWidth(), mStillSize.getHeight(),
                    ImageFormat.JPEG, IMAGE_READER_MAX_IMAGES);
            mImageReader.setOnImageAvailableListener(mImageAvailableListener, mCameraHandler);

            manager.openCamera(cameraId, mStateCallback, mCameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed: " + e);
            mCameraOpenLock.release();
            if (mCallback != null) mCallback.onError("Open failed: " + e.getMessage());
        }
    }

    public void startBurst() {
        if (mBurstActive.getAndSet(true)) return;
        mShotsFired.set(0);
        mShotsSaved.set(0);

        Log.i(TAG, "Burst START (Locked Infinity Focus)");
        if (mCallback != null) mCallback.onBurstStarted();

        mCameraHandler.post(this::startRepeatingBurstStream);
    }

    public void stopBurst() {
        if (!mBurstActive.getAndSet(false)) return;
        Log.i(TAG, "Burst STOP after " + mShotsFired.get() + " shots");
        if (mCallback != null)
            mCallback.onBurstStopped(mShotsFired.get(), mShotsSaved.get());
        resumePreview();
    }

    public int pendingSaveCount() { return mSaveQueue.size(); }

    public void release() {
        mBurstActive.set(false);
        try {
            mCameraOpenLock.acquire();
            if (mCaptureSession != null) {
                mCaptureSession.close(); mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close(); mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close(); mImageReader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "release error: " + e);
        } finally {
            mCameraOpenLock.release();
        }
        if (mCameraThread != null) {
            mCameraThread.quitSafely();
            try { mCameraThread.join(); } catch (InterruptedException ignored) {}
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice device) {
            mCameraOpenLock.release();
            mCameraDevice = device;
            createCaptureSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice device) {
            mCameraOpenLock.release();
            device.close(); mCameraDevice = null;
        }
        @Override
        public void onError(@NonNull CameraDevice device, int error) {
            mCameraOpenLock.release();
            device.close(); mCameraDevice = null;
            if (mCallback != null) mCallback.onError("Camera device error: " + error);
        }
    };

    private void createCaptureSession() {
        try {
            List<Surface> surfaces = Arrays.asList(mPreviewSurface, mImageReader.getSurface());

            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewBuilder.addTarget(mPreviewSurface);
            applyPreviewSettings(mPreviewBuilder);

            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mCaptureSession = session;
                    resumePreview();
                    if (mCallback != null) mCallback.onReady(mStillSize);
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    if (mCallback != null) mCallback.onError("Session configure failed");
                }
            }, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession: " + e);
        }
    }

    private void resumePreview() {
        if (mCaptureSession == null || mPreviewBuilder == null) return;
        try {
            applyPreviewSettings(mPreviewBuilder);
            mCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), null, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "resumePreview: " + e);
        }
    }

    private void startRepeatingBurstStream() {
        if (!mBurstActive.get() || mCaptureSession == null || mCameraDevice == null) return;

        try {
            mBurstBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mBurstBuilder.addTarget(mPreviewSurface);
            mBurstBuilder.addTarget(mImageReader.getSurface());
            applyBurstSettings(mBurstBuilder);

            mCaptureSession.setRepeatingRequest(mBurstBuilder.build(), mBurstCaptureCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startRepeatingBurstStream failed: " + e);
            stopBurst();
        }
    }

    private final CameraCaptureSession.CaptureCallback mBurstCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull TotalCaptureResult result) {
            if (mBurstActive.get()) {
                int idx = mShotsFired.incrementAndGet();
                if (mCallback != null) mCallback.onShotFired(idx);
            }
        }
        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                     @NonNull CaptureRequest request,
                                     @NonNull CaptureFailure failure) {
            Log.w(TAG, "Capture failed");
        }
    };

    private final ImageReader.OnImageAvailableListener mImageAvailableListener = listener -> {
        Image image = null;
        try {
            image = listener.acquireNextImage();
            if (image == null) return;

            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes   = new byte[buf.remaining()];
            buf.get(bytes);
            long ts  = image.getTimestamp();
            int  idx = mShotsSaved.incrementAndGet();

            boolean queued = mSaveQueue.offer(new PendingShot(bytes, ts, idx));
            if (!queued) Log.w(TAG, "Save queue full, dropped frame #" + idx);

            if (mCallback != null) mCallback.onFrameQueued(idx, mSaveQueue.size());
        } catch (Exception e) {
            Log.e(TAG, "ImageAvailable error: " + e);
        } finally {
            if (image != null) image.close();
        }
    };

    private void applyPreviewSettings(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        // Manual focus locked to infinity
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);

        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AE_LOCK, false);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
    }

    private void applyBurstSettings(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        // Manual focus locked to infinity
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);

        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AE_LOCK, false);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        b.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY_BURST);
    }

    private Size pickBestBurstSize(CameraManager manager, String cameraId) throws CameraAccessException {
        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(1920, 1080);

        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);

        Size best = sizes[sizes.length - 1];
        long bestPx = 0;

        for (Size s : sizes) {
            long px = (long) s.getWidth() * s.getHeight();
            if (px <= MAX_BURST_PIXELS && px > bestPx) {
                best = s;
                bestPx = px;
            }
        }
        return best;
    }

    public PendingShot pollPendingShot() { return mSaveQueue.poll(); }
    public ArrayBlockingQueue<PendingShot> getSaveQueue() { return mSaveQueue; }

    public interface EngineCallback {
        void onReady(Size stillSize);
        void onBurstStarted();
        void onShotFired(int count);
        void onFrameQueued(int count, int queueDepth);
        void onBurstStopped(int fired, int saved);
        void onError(String reason);
    }
}
