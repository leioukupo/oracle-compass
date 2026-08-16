package com.magneo.compass;

import android.app.Activity;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.magneo.compass.ui.Ui;

/** 罗盘风格返回按钮：居中放置，避免圆屏四角裁切；沉浸式全屏下保证任何页面都能返回。 */
public class BackButton extends Button {
    public BackButton(Activity a) {
        super(a);
        setText("◀ 返回");
        Ui.stylePillButton(this);
        setOnClickListener(v -> a.finish());
        int d = (int) (18 * a.getResources().getDisplayMetrics().density);
        setPadding(d, d / 2, d, d / 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        setLayoutParams(lp);
    }
}
