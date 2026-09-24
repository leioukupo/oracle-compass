package com.magneo.compass;

import android.graphics.SurfaceTexture;
import android.view.TextureView;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/** WebRTC sink backed by TextureView for old MTK Android compositors. */
public final class K230WebRtcTextureRenderer implements VideoSink, K230WebRtcReceiver.ManagedRenderer {
    private final TextureView view;
    private EglRenderer eglRenderer;
    private boolean initialized;
    private SurfaceTexture texture;

    public K230WebRtcTextureRenderer(TextureView value) {
        view = value;
    }

    @Override public synchronized void initRenderer(EglBase.Context context) {
        if (initialized || context == null) return;
        eglRenderer = new EglRenderer("K230WebRtcTexture");
        eglRenderer.init(context, EglBase.CONFIG_PLAIN, new GlRectDrawer());
        initialized = true;
        if (view != null && view.isAvailable()) {
            onSurfaceTextureAvailable(view.getSurfaceTexture());
        }
    }

    public synchronized void onSurfaceTextureAvailable(SurfaceTexture value) {
        texture = value;
        if (initialized && eglRenderer != null && value != null) {
            eglRenderer.createEglSurface(value);
        }
    }

    public synchronized boolean onSurfaceTextureDestroyed(SurfaceTexture value) {
        if (texture == value) texture = null;
        if (eglRenderer != null) {
            eglRenderer.releaseEglSurface(() -> {});
        }
        return true;
    }

    @Override public void onFrame(VideoFrame frame) {
        EglRenderer target;
        synchronized (this) { target = initialized ? eglRenderer : null; }
        if (target != null) {
            target.onFrame(frame);
        } else if (frame != null) {
            frame.release();
        }
    }

    @Override public synchronized void releaseRenderer() {
        initialized = false;
        texture = null;
        if (eglRenderer != null) {
            try { eglRenderer.release(); } catch (Exception ignored) {}
            eglRenderer = null;
        }
    }
}
