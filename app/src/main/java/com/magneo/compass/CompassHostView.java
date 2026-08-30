package com.magneo.compass;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 主罗盘容器：默认用 OpenGL 绘制普通主盘，Canvas 视图叠在上层负责触摸、
 * 详情页、拖拽预览和错误状态。GL 不可用时自动退回完整 Canvas。
 */
public class CompassHostView extends FrameLayout {
    private final SensorHub hub;
    private final CompassView.Actions actions;
    private final CompassView overlay;
    private CompassGlView glView;
    private String activeRenderer = "";
    private boolean glFailed;

    public CompassHostView(Context context, SensorHub hub, CompassView.Actions actions) {
        super(context);
        this.hub = hub;
        this.actions = actions;
        setClipChildren(false);
        overlay = new CompassView(context, hub, actions);
        applyRendererPrefs();
    }

    public void applyRendererPrefs() {
        String wanted = Prefs.mainRenderer(getContext());
        if (Prefs.MAIN_RENDERER_GL.equals(wanted) && !glFailed) {
            if (!Prefs.MAIN_RENDERER_GL.equals(activeRenderer)) useGl();
        } else {
            if (!Prefs.MAIN_RENDERER_CANVAS.equals(activeRenderer)) useCanvas();
        }
    }

    private void useGl() {
        removeAllViews();
        try {
            glView = new CompassGlView(getContext(), hub, new Runnable() {
                @Override public void run() {
                    glFailed = true;
                    post(new Runnable() {
                        @Override public void run() {
                            useCanvas();
                        }
                    });
                }
            });
            addView(glView, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.setGlMainMode(true);
            addView(overlay, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            activeRenderer = Prefs.MAIN_RENDERER_GL;
        } catch (Throwable t) {
            glFailed = true;
            useCanvas();
        }
    }

    private void useCanvas() {
        removeAllViews();
        if (glView != null) {
            try { glView.onPause(); } catch (Throwable ignored) {}
        }
        glView = null;
        overlay.setGlMainMode(false);
        addView(overlay, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        activeRenderer = Prefs.MAIN_RENDERER_CANVAS;
    }

    public void onHostResume() {
        if (glView != null) {
            try { glView.onResume(); } catch (Throwable ignored) {}
        }
    }

    public void onHostPause() {
        if (glView != null) {
            try { glView.onPause(); } catch (Throwable ignored) {}
        }
    }

    public boolean isUsingGl() {
        return glView != null && Prefs.MAIN_RENDERER_GL.equals(activeRenderer);
    }

    public void setStatus(String s) {
        overlay.setStatus(s);
        requestRender();
    }

    public void onBatteryStateChanged() {
        overlay.onBatteryStateChanged();
    }

    public void toggleDetail() {
        overlay.toggleDetail();
        requestRender();
    }

    public void syncHardwareDemand() {
        overlay.syncHardwareDemand();
    }

    public boolean isOracleDetailActive() {
        return overlay.isOracleDetailActive();
    }

    public boolean isOracleCollecting() {
        return overlay.isOracleCollecting();
    }

    public void setOracleAiResult(long readingId, String text, String status) {
        overlay.setOracleAiResult(readingId, text, status);
    }

    @Override
    public void postInvalidate() {
        super.postInvalidate();
        overlay.postInvalidate();
        requestRender();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        overlay.invalidate();
        requestRender();
    }

    private void requestRender() {
        if (glView != null) glView.requestRenderSafe();
    }
}
