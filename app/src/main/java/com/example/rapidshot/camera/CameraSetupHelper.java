package com.example.rapidshot.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CameraSetupHelper
 *
 * Queries Camera2 characteristics to pick the optimal camera and
 * report what it supports — so the UI can show real specs instead of guesses.
 */
public class CameraSetupHelper {

    private static final String TAG = "RapidShot/Setup";

    public static class CameraInfo {
        public String  cameraId;
        public int     lensFacing;       // CameraCharacteristics.LENS_FACING_*
        public Size    maxJpegSize;
        public long    maxJpegPixels;
        public int     megapixels;
        public Size[]  allJpegSizes;
        public Range<Integer>[] fpsRanges;
        public boolean hasOis;
        public int     hardwareLevel;    // LEGACY=0 LIMITED=1 FULL=2 LEVEL3=3
        public String  summary;

        @Override public String toString() { return summary; }
    }

    /** Returns info for all back-facing cameras, sorted by megapixels desc. */
    public static List<CameraInfo> enumerateBackCameras(Context ctx) {
        List<CameraInfo> results = new ArrayList<>();
        CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;

                CameraInfo info = buildInfo(id, chars);
                results.add(info);
                Log.i(TAG, "Camera " + id + ": " + info.summary);
            }
        } catch (Exception e) {
            Log.e(TAG, "enumerate: " + e);
        }
        results.sort((a, b) -> Long.compare(b.maxJpegPixels, a.maxJpegPixels));
        return results;
    }

    /** Returns info for the front camera (first found). */
    public static CameraInfo getFrontCamera(Context ctx) {
        CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return buildInfo(id, chars);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getFront: " + e);
        }
        return null;
    }

    /** Best single back camera: highest megapixel count. */
    public static CameraInfo getBestBackCamera(Context ctx) {
        List<CameraInfo> list = enumerateBackCameras(ctx);
        return list.isEmpty() ? null : list.get(0);
    }

    @SuppressWarnings("unchecked")
    private static CameraInfo buildInfo(String id, CameraCharacteristics chars) {
        CameraInfo info = new CameraInfo();
        info.cameraId   = id;
        info.lensFacing  = chars.get(CameraCharacteristics.LENS_FACING);

        StreamConfigurationMap map = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map != null) {
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            info.allJpegSizes = sizes != null ? sizes : new Size[0];

            Size best = new Size(0, 0);
            long bestPx = 0;
            for (Size s : info.allJpegSizes) {
                long px = (long) s.getWidth() * s.getHeight();
                if (px > bestPx) { best = s; bestPx = px; }
            }
            info.maxJpegSize   = best;
            info.maxJpegPixels = bestPx;
            info.megapixels    = (int) (bestPx / 1_000_000);
        }

        Integer hwLevel = chars.get(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        info.hardwareLevel = hwLevel != null ? hwLevel : 0;

        Range<Integer>[] ranges = chars.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        info.fpsRanges = ranges != null ? ranges : new Range[0];

        Boolean ois = chars.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) != null;
        int[] oisModes = chars.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        info.hasOis = oisModes != null && oisModes.length > 1;

        String levelStr;
        switch (info.hardwareLevel) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:  levelStr = "LEGACY"; break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: levelStr = "LIMITED"; break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:    levelStr = "FULL"; break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:       levelStr = "LEVEL3"; break;
            default: levelStr = "UNKNOWN";
        }

        info.summary = "cam" + id + " | " + info.megapixels + "MP | " +
                       info.maxJpegSize + " | " + levelStr + " | OIS=" + info.hasOis;
        return info;
    }
}
