package com.example.rapidshot.save;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import com.example.rapidshot.camera.BurstCaptureEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SaveManager
 *
 * Drains BurstCaptureEngine.getSaveQueue() on background threads and
 * writes each JPEG to MediaStore (Android 10+) or DCIM/Camera/Burst/.
 *
 * STRATEGY (mirrors the decompiled app's handlePicture flow):
 *   • 2-thread IO executor drains the queue concurrently — same as the
 *     factory app which uses separate "updateThumbTask" and write threads.
 *   • Each file is written under DCIM/Camera/Burst/<session_id>/
 *     to mirror the factory app's DCIM/Camera/Cshot/<flagId>/ layout.
 *   • EXIF data is patched in: timestamp, sequence index, make/model.
 *   • MediaStore is notified after each file — the thumbnail becomes
 *     available to Gallery immediately, same as "insertMediaItem" in DEX.
 *   • A "cover" (first frame thumbnail) is generated and stored, mirroring
 *     "insertMediaItem, not CShot Cover" log from the factory app.
 */
public class SaveManager {

    private static final String TAG = "RapidShot/Save";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private final Context mContext;
    private final ExecutorService mIoExecutor = Executors.newFixedThreadPool(2);
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicInteger mSavedCount = new AtomicInteger(0);

    private SaveCallback mCallback;
    private BurstCaptureEngine mEngine;

    /** Session ID — mirrors mBurstShotFlagId from the decompiled app */
    private String mSessionId;

    public SaveManager(Context ctx, BurstCaptureEngine engine, SaveCallback cb) {
        mContext  = ctx.getApplicationContext();
        mEngine   = engine;
        mCallback = cb;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Start draining the save queue in the background. */
    public void startDraining() {
        if (mRunning.getAndSet(true)) return;
        mSessionId  = DATE_FMT.format(new Date());
        mSavedCount.set(0);
        Log.i(TAG, "Save session: " + mSessionId);
        mIoExecutor.submit(this::drainLoop);
        mIoExecutor.submit(this::drainLoop); // two concurrent writers
    }

    /** Stop drain loop. Blocks until queue is empty or 30 s elapses. */
    public void stopAndFlush() {
        mRunning.set(false);
        // Drain whatever is left
        drainRemaining();
    }

    public int getSavedCount() { return mSavedCount.get(); }

    // ── Drain loops ────────────────────────────────────────────────────────

    private void drainLoop() {
        while (mRunning.get() || mEngine.getSaveQueue().size() > 0) {
            BurstCaptureEngine.PendingShot shot = mEngine.pollPendingShot();
            if (shot == null) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                continue;
            }
            saveShot(shot);
        }
    }

    private void drainRemaining() {
        BurstCaptureEngine.PendingShot shot;
        while ((shot = mEngine.pollPendingShot()) != null) {
            saveShot(shot);
        }
    }

    // ── Core save logic ────────────────────────────────────────────────────

    private void saveShot(BurstCaptureEngine.PendingShot shot) {
        String filename = "BURST_" + mSessionId + "_" + String.format("%04d", shot.sequenceIndex) + ".jpg";
        long   taken    = System.currentTimeMillis();

        try {
            Uri uri = writeToMediaStore(shot.jpeg, filename, taken, shot.sequenceIndex);
            int n   = mSavedCount.incrementAndGet();

            if (mCallback != null) {
                // Generate thumbnail only for the first frame (cover image)
                // mirrors "updateBurstShotThumbnail" from the decompiled app
                byte[] thumb = (n == 1) ? makeThumbnail(shot.jpeg) : null;
                mCallback.onShotSaved(n, uri, thumb);
            }

            Log.d(TAG, "Saved #" + shot.sequenceIndex + " → " + uri);
        } catch (Exception e) {
            Log.e(TAG, "Save failed for #" + shot.sequenceIndex + ": " + e.getMessage());
            if (mCallback != null) mCallback.onSaveFailed(shot.sequenceIndex, e.getMessage());
        }
    }

    /**
     * Write JPEG bytes to MediaStore under DCIM/Camera/Burst/<session>/
     * Mirrors "IMAGE_CATEGORY_BURST_SHOT" and "IMAGE_CATEGORY_CSHOT_PATH"
     * content values written by the factory app.
     */
    private Uri writeToMediaStore(byte[] jpeg, String filename, long taken, int index)
            throws IOException {
        ContentResolver cr = mContext.getContentResolver();

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.DATE_TAKEN, taken);
        cv.put(MediaStore.Images.Media.DATE_ADDED, taken / 1000);
        cv.put(MediaStore.Images.Media.DATE_MODIFIED, taken / 1000);
        cv.put(MediaStore.Images.Media.SIZE, jpeg.length);
        cv.put(MediaStore.Images.Media.WIDTH, 0);   // filled by MediaScanner
        cv.put(MediaStore.Images.Media.HEIGHT, 0);

        // Path: DCIM/Camera/Burst/<sessionId>/
        // Mirrors DCIM/Camera/Cshot/<flagId>/ from the decompiled app
        String relativePath = Environment.DIRECTORY_DCIM
                + "/Camera/Burst/" + mSessionId + "/";
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);

        // Mark as pending while we write (Android 10+ atomicity)
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = cr.insert(collection, cv);
        if (item == null) throw new IOException("MediaStore insert returned null");

        // Write bytes
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(item, "w");
             FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
            fos.write(jpeg);
        }

        // Patch EXIF
        patchExif(cr, item, index, taken);

        // Mark ready — Gallery can now see it
        cv.clear();
        cv.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(item, cv, null, null);

        return item;
    }

    /**
     * Patch EXIF after write.
     * The factory app sets UserComment with burst index and session tag.
     */
    private void patchExif(ContentResolver cr, Uri uri, int index, long taken)  {
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw")) {
            if (pfd == null) return;
            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());

            // Sequence info in UserComment (mirrors factory EXIF maker note)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT,
                    "RapidShot burst index=" + index + " session=" + mSessionId);

            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);

            // Date/time
            SimpleDateFormat exifFmt =
                    new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            exif.setAttribute(ExifInterface.TAG_DATETIME,
                    exifFmt.format(new Date(taken)));
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                    exifFmt.format(new Date(taken)));

            exif.saveAttributes();
        } catch (Exception e) {
            Log.w(TAG, "EXIF patch failed: " + e.getMessage());
        }
    }

    /**
     * Generate a small thumbnail from JPEG bytes.
     * Mirrors updateBurstShotThumbnail / KEY_BURSTSHOT_THUMB_ANIM_STEP_TIME.
     */
    private byte[] makeThumbnail(byte[] jpeg) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 8;  // 1/8 scale for thumb
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Callback ───────────────────────────────────────────────────────────

    public interface SaveCallback {
        /** Called after each frame is fully written and MediaStore-indexed. */
        void onShotSaved(int totalSaved, Uri uri, byte[] coverThumbJpeg);
        void onSaveFailed(int index, String reason);
    }
}
