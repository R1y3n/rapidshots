# RapidShot — Unlimited Burst for ColorOS / OPPO Devices

No root. No Shizuku. Normal user APK. Works on Android 10+.

---

## What this does

Replicates OPPO's volume-hold burst (`CShot`) using stock Camera2 API,
with **no shot limit** :)

---

## Algorithm (reverse-engineered from factory app)

### Factory app flow (from DEX strings):
```
onBurstShotStart()
  → trySendRepeatBurstCaptureMessage()      ← looping submit
    → submitCapture(TEMPLATE_STILL_CAPTURE)
    → onCaptureCompleted → trySendRepeatBurstCaptureMessage   ← chain
  → [limit: mMtkCShortRequestNum reach max] ← MTK HAL cap we bypass
onBurstShotEnd()
  → deleteBurstShotFlagFile()
  → handlePicture() → mRecBurstNum++
```

### RapidShot flow:
```
VOL▼ held (500ms) OR shutter held
  → BurstCaptureEngine.startBurst()
    → lockAeAf()                            ← mirrors mbPreburstShotCapture
    → submitOneBurstCapture()
      → CaptureSession.capture(STILL_CAPTURE)
      → onCaptureCompleted → submitOneBurstCapture()  ← same chain, no limit
  → Image arrives → ImageReader → PendingShot queued
  → SaveManager (2 threads) drains queue → MediaStore write → EXIF patch

VOL▼ released
  → stopBurst() → resumePreview()
  → SaveManager finishes draining queue asynchronously
```

### Why quality matches factory CShot (not a "burst mode" downgrade):
| Setting | Value | Source |
|---|---|---|
| JPEG_QUALITY | 95 | Extracted from factory CShot path |
| NOISE_REDUCTION_MODE | FAST | CShot trades HIGH_QUALITY for speed |
| EDGE_MODE | FAST | Same |
| AE_LOCK during burst | true | Consistent exposure across frames |
| AWB_LOCK during burst | true | Consistent white balance |
| AF_MODE | CONTINUOUS_PICTURE | Factory uses same |
| Resolution | Max sensor output | 50MP on FindX5 Pro, auto-detected |
| Image format | JPEG | Factory CShot is JPEG, not HEIF |

The factory app's CShot does **not** use MFNR/AINR/HDR — those are
single-shot only. So JPEG FAST-NR is exactly on par with the factory.

---

## Build

1. Open in Android Studio Flamingo or later.
2. `Build → Make Project`
3. `Run → Run 'app'` on your OPPO device.

Or from CLI:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Output location

```
DCIM/Camera/Burst/<session_timestamp>/
    BURST_20241023_143022_000_0001.jpg
    BURST_20241023_143022_000_0002.jpg
    ...
```
Mirrors factory: `DCIM/Camera/Cshot/<flagId>/`

---

## Trigger options

| Action | Result |
|---|---|
| Tap shutter button | Single shot |
| Hold shutter button (>500ms) | Burst until release |
| Hold VOL ▼ (>500ms) | Burst until release |
| VOL ▼ tap | Single shot |

---

## Known limits vs factory app

| Limit | Factory | RapidShot |
|---|---|---|
| Shot cap | ~20 (MTK HAL: `mMtkCShortRequestNum reach max`) | None (queue-bounded by RAM) |
| HEIF encoding | Yes (via APS) | No — JPEG only |
| AINR post-processing | Per-shot | Not applicable in burst |
| RAW output | Not in CShot | Not in burst (JPEG only) |
| Thermal throttle | Yes (stops recording) | Queue pressure warning only |

---

## RAM usage

At 50MP JPEG ~15MB each, 200 queued shots = ~3GB. The queue cap
(`SAVE_QUEUE_MAX = 200`) prevents OOM. Real throughput on Snapdragon
is ~4–8 fps for full-resolution JPEG, so the queue drains faster than
it fills at typical burst durations.

To raise the cap, edit `BurstCaptureEngine.SAVE_QUEUE_MAX`.
