package com.magneo.compass;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import java.util.List;

/** Android 5.1 兼容的手电控制。旧 Camera API 需要持有预览才能维持 torch。 */
public final class FlashlightController {
    private static Camera camera;
    private static SurfaceTexture previewTexture;
    private static boolean requested;
    private static boolean enabled;

    private FlashlightController() {}

    public static synchronized boolean isOn() {
        return enabled;
    }

    public static synchronized boolean isRequestedOn() {
        return requested;
    }

    public static synchronized boolean toggle() throws Exception {
        if (requested) {
            turnOff();
            return false;
        }
        requested = true;
        turnOn();
        return true;
    }

    public static synchronized void turnOn() throws Exception {
        if (enabled) return;
        Camera cam = null;
        SurfaceTexture texture = null;
        try {
            cam = Camera.open(preferBackCamera());
            Camera.Parameters p = cam.getParameters();
            String mode = chooseTorchMode(p);
            if (mode == null) throw new IllegalStateException("设备不支持闪光灯");
            p.setFlashMode(mode);
            cam.setParameters(p);
            texture = new SurfaceTexture(0);
            try { cam.setPreviewTexture(texture); } catch (Throwable ignored) {}
            cam.startPreview();
            camera = cam;
            previewTexture = texture;
            enabled = true;
        } catch (Exception e) {
            safeRelease(cam);
            if (texture != null) {
                try { texture.release(); } catch (Throwable ignored) {}
            }
            enabled = false;
            camera = null;
            previewTexture = null;
            throw e;
        }
    }

    public static synchronized void restoreIfRequested() {
        if (!requested || enabled) return;
        try { turnOn(); } catch (Throwable ignored) {}
    }

    public static synchronized void releaseHardwareKeepingRequest() {
        releaseHardware(false);
    }

    public static synchronized boolean applyToOpenedCamera(Camera cam) {
        if (cam == null) return false;
        try {
            Camera.Parameters p = cam.getParameters();
            if (requested) {
                String mode = chooseTorchMode(p);
                if (mode == null) return false;
                p.setFlashMode(mode);
            } else {
                List<String> modes = p.getSupportedFlashModes();
                if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
                    p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                }
            }
            cam.setParameters(p);
            return requested;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized void turnOff() {
        requested = false;
        releaseHardware(true);
    }

    private static void releaseHardware(boolean clearRequest) {
        if (clearRequest) requested = false;
        Camera cam = camera;
        SurfaceTexture texture = previewTexture;
        camera = null;
        previewTexture = null;
        enabled = false;
        if (cam != null) {
            try {
                Camera.Parameters p = cam.getParameters();
                List<String> modes = p.getSupportedFlashModes();
                if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
                    p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    cam.setParameters(p);
                }
            } catch (Throwable ignored) {}
            try { cam.stopPreview(); } catch (Throwable ignored) {}
            safeRelease(cam);
        }
        if (texture != null) {
            try { texture.release(); } catch (Throwable ignored) {}
        }
    }

    private static int preferBackCamera() {
        try {
            int count = Camera.getNumberOfCameras();
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < count; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String chooseTorchMode(Camera.Parameters p) {
        List<String> modes = p.getSupportedFlashModes();
        if (modes == null) return null;
        if (modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
            return Camera.Parameters.FLASH_MODE_TORCH;
        }
        if (modes.contains(Camera.Parameters.FLASH_MODE_ON)) {
            return Camera.Parameters.FLASH_MODE_ON;
        }
        return null;
    }

    private static void safeRelease(Camera cam) {
        if (cam == null) return;
        try { cam.release(); } catch (Throwable ignored) {}
    }
}
