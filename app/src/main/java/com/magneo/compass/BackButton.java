package com.magneo.compass;

import android.app.Activity;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.Button;

/** 罗盘风格返回按钮：沉浸式全屏下保证任何页面都能返回。 */
public class BackButton extends Button {
    public BackButton(Activity a) {
        super(a);
        setText("◀ 返回");
        setTextColor(Color.rgb(232, 220, 192));
        setBackgroundColor(Color.rgb(30, 30, 30));
        setOnClickListener(v -> a.finish());
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
