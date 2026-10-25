package com.qilai.zouzou;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.FaceDetector;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 打开前置摄像头取几帧，判断画面里有没有人脸，然后立即关闭摄像头。
 * 每次检查约 1.5–2.5 秒。不保存、不上传任何画面。
 *
 * 优先用摄像头芯片自带的人脸检测（不需要处理画面），
 * 不支持或没检测到时，再用系统自带的 android.media.FaceDetector 分析一帧灰度图。
 */
public final class FaceChecker {

    public static final int RESULT_NONE = 0;
    public static final int RESULT_FACE = 1;
    public static final int RESULT_NO_FACE = 2;
    public static final int RESULT_DARK = 3;
    public static final int RESULT_ERROR = 4;
    public static final int RESULT_NO_PERMISSION = 5;
    public static final int RESULT_NO_CAMERA = 6;
    public static final int RESULT_FLAT = 7;
    public static final int RESULT_BUSY = 8;

    public interface Callback {
        /** 在 FaceChecker 自己的后台线程上回调。 */
        void onResult(int result);
    }

    private static final long WARMUP_MS = 1500;
    private static final long NO_FRAME_GIVE_UP_MS = 3500;
    private static final long TIMEOUT_MS = 7000;
    private static final int DARK_LUMA = 22;

    private final Context ctx;
    private final CameraManager manager;
    private final HandlerThread thread;
    private final Handler bg;
    private Session current;
    private boolean released;

    public FaceChecker(Context c) {
        ctx = c.getApplicationContext();
        manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        thread = new HandlerThread("face-check");
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    public void check(final Callback cb) {
        bg.post(new Runnable() {
            @Override public void run() {
                if (released) { cb.onResult(RESULT_ERROR); return; }
                if (current != null) { cb.onResult(RESULT_BUSY); return; }
                new Session(cb).start();
            }
        });
    }

    /** 关闭摄像头（如果正在用）并结束后台线程。 */
    public void release() {
        bg.post(new Runnable() {
            @Override public void run() {
                released = true;
                if (current != null) current.finish(RESULT_ERROR);
                thread.quitSafely();
            }
        });
    }

    private final class Session {
        final Callback cb;
        CameraDevice device;
        CameraCaptureSession session;
        ImageReader reader;
        boolean done;
        long openedAt;
        int width, height, sensorOrientation = 270;
        int faceMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF;
        int halHits;
        byte[] luma;
        boolean haveFrame;

        Session(Callback cb) { this.cb = cb; }

        final Runnable timeout = new Runnable() {
            @Override public void run() { finish(RESULT_ERROR); }
        };

        final Runnable evaluate = new Runnable() {
            @Override public void run() {
                if (done) return;
                if (!haveFrame) {
                    if (SystemClock.elapsedRealtime() - openedAt < NO_FRAME_GIVE_UP_MS) {
                        bg.postDelayed(this, 300);
                    } else {
                        finish(RESULT_ERROR);
                    }
                    return;
                }
                byte[] y = luma;
                int w = width, h = height, so = sensorOrientation;
                closeCamera(); // 先关摄像头，再慢慢分析
                if (meanLuma(y) < DARK_LUMA) { finish(RESULT_DARK); return; }
                boolean found;
                try {
                    found = detect(y, w, h, so);
                } catch (RuntimeException e) {
                    finish(RESULT_ERROR);
                    return;
                }
                finish(found ? RESULT_FACE : RESULT_NO_FACE);
            }
        };

        void start() {
            current = this;
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                finish(RESULT_NO_PERMISSION);
                return;
            }
            try {
                String id = null;
                CameraCharacteristics chars = null;
                for (String cid : manager.getCameraIdList()) {
                    CameraCharacteristics c = manager.getCameraCharacteristics(cid);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraMetadata.LENS_FACING_FRONT) {
                        id = cid;
                        chars = c;
                        break;
                    }
                }
                if (id == null) { finish(RESULT_NO_CAMERA); return; }

                Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensorOrientation = so;
                int[] modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                if (modes != null) {
                    for (int m : modes) {
                        if (m == CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) faceMode = m;
                    }
                    if (faceMode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) {
                        for (int m : modes) {
                            if (m == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) faceMode = m;
                        }
                    }
                }
                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size size = chooseSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
                if (size == null) { finish(RESULT_ERROR); return; }
                width = size.getWidth();
                height = size.getHeight();

                reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3);
                reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override public void onImageAvailable(ImageReader r) { onFrame(r); }
                }, bg);
                bg.postDelayed(timeout, TIMEOUT_MS);

                manager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice d) {
                        if (done) { d.close(); return; }
                        device = d;
                        openedAt = SystemClock.elapsedRealtime();
                        createSession();
                    }

                    @Override public void onDisconnected(CameraDevice d) {
                        try { d.close(); } catch (RuntimeException ignored) { }
                        if (device == d) device = null;
                        finish(RESULT_BUSY);
                    }

                    @Override public void onError(CameraDevice d, int error) {
                        try { d.close(); } catch (RuntimeException ignored) { }
                        if (device == d) device = null;
                        boolean busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                                || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;
                        finish(busy ? RESULT_BUSY : RESULT_ERROR);
                    }
                }, bg);
            } catch (CameraAccessException e) {
                int r = e.getReason();
                boolean busy = r == CameraAccessException.CAMERA_IN_USE || r == CameraAccessException.MAX_CAMERAS_IN_USE;
                finish(busy ? RESULT_BUSY : RESULT_ERROR);
            } catch (SecurityException e) {
                finish(RESULT_NO_PERMISSION);
            } catch (RuntimeException e) {
                finish(RESULT_ERROR);
            }
        }

        @SuppressWarnings("deprecation")
        void createSession() {
            try {
                device.createCaptureSession(Arrays.asList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        if (done) {
                            try { s.close(); } catch (RuntimeException ignored) { }
                            return;
                        }
                        session = s;
                        try {
                            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            b.addTarget(reader.getSurface());
                            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            if (faceMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) {
                                b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode);
                            }
                            s.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                                @Override public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest req, TotalCaptureResult res) {
                                    onMeta(res);
                                }
                            }, bg);
                            bg.postDelayed(evaluate, WARMUP_MS);
                        } catch (CameraAccessException e) {
                            finish(RESULT_ERROR);
                        } catch (RuntimeException e) {
                            finish(RESULT_ERROR);
                        }
                    }

                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        finish(RESULT_ERROR);
                    }
                }, bg);
            } catch (CameraAccessException e) {
                finish(RESULT_ERROR);
            } catch (RuntimeException e) {
                finish(RESULT_ERROR);
            }
        }

        void onMeta(TotalCaptureResult res) {
            if (done) return;
            Face[] faces = res.get(CaptureResult.STATISTICS_FACES);
            if (faces == null || faces.length == 0) return;
            for (Face f : faces) {
                if (f != null && f.getScore() >= 30) { halHits++; break; }
            }
            if (halHits >= 2 && SystemClock.elapsedRealtime() - openedAt >= 300) finish(RESULT_FACE);
        }

        void onFrame(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null || done) return;
                // 刚打开时曝光还没稳定，前几帧偏暗，跳过
                if (SystemClock.elapsedRealtime() - openedAt < WARMUP_MS - 500) return;
                Image.Plane p = img.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                int rowStride = p.getRowStride();
                int pixStride = p.getPixelStride();
                if (luma == null) luma = new byte[width * height];
                for (int yy = 0; yy < height; yy++) {
                    int rowStart = yy * rowStride;
                    if (pixStride == 1) {
                        buf.position(rowStart);
                        buf.get(luma, yy * width, width);
                    } else {
                        for (int xx = 0; xx < width; xx++) {
                            luma[yy * width + xx] = buf.get(rowStart + xx * pixStride);
                        }
                    }
                }
                haveFrame = true;
            } catch (RuntimeException ignored) {
                // 单帧出错就跳过
            } finally {
                if (img != null) img.close();
            }
        }

        void finish(int result) {
            if (done) return;
            done = true;
            bg.removeCallbacks(evaluate);
            bg.removeCallbacks(timeout);
            closeCamera();
            if (current == this) current = null;
            try {
                cb.onResult(result);
            } catch (RuntimeException ignored) {
            }
        }

        void closeCamera() {
            try { if (session != null) session.close(); } catch (RuntimeException ignored) { }
            session = null;
            try { if (device != null) device.close(); } catch (RuntimeException ignored) { }
            device = null;
            try { if (reader != null) reader.close(); } catch (RuntimeException ignored) { }
            reader = null;
        }
    }

    static Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            int big = Math.max(s.getWidth(), s.getHeight());
            int small = Math.min(s.getWidth(), s.getHeight());
            if (big < 320 || small < 240) continue;
            long area = (long) s.getWidth() * s.getHeight();
            long score = Math.abs(area - 640L * 480L);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    static int meanLuma(byte[] y) {
        long sum = 0;
        int n = 0;
        for (int i = 0; i < y.length; i += 7) {
            sum += y[i] & 0xFF;
            n++;
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    /** 在灰度图上找人脸；手机可能竖放或横放，所以四个方向都试一下。 */
    static boolean detect(byte[] y, int w, int h, int sensorOrientation) {
        int step = 1;
        while (w / step > 640 || h / step > 640) step++;
        int sw = (w / step) & ~1;
        int sh = (h / step) & ~1;
        if (sw < 64 || sh < 64) return false;
        int[] px = new int[sw * sh];
        int base = ((sensorOrientation % 360) + 360) % 360;
        int[] order = {base, (base + 180) % 360, (base + 90) % 360, (base + 270) % 360};
        for (int rot : order) {
            boolean swap = rot == 90 || rot == 270;
            int bw = swap ? sh : sw;
            int bh = swap ? sw : sh;
            for (int dy = 0; dy < bh; dy++) {
                for (int dx = 0; dx < bw; dx++) {
                    int sx, sy;
                    if (rot == 90) { sx = dy; sy = sh - 1 - dx; }
                    else if (rot == 180) { sx = sw - 1 - dx; sy = sh - 1 - dy; }
                    else if (rot == 270) { sx = sw - 1 - dy; sy = dx; }
                    else { sx = dx; sy = dy; }
                    int v = y[(sy * step) * w + sx * step] & 0xFF;
                    px[dy * bw + dx] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
            bmp.setPixels(px, 0, bw, 0, 0, bw, bh);
            FaceDetector fd = new FaceDetector(bw, bh, 3);
            FaceDetector.Face[] faces = new FaceDetector.Face[3];
            int n = fd.findFaces(bmp, faces);
            bmp.recycle();
            for (int i = 0; i < n; i++) {
                if (faces[i] != null && faces[i].confidence() >= 0.4f) return true;
            }
        }
        return false;
    }
}
