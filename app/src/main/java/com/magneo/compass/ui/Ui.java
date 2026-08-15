package com.magneo.compass.ui;

import android.content.Context;

/**
 * 统一的 dp/sp 换算与字号/触控目标常量。
 * 取代原先散落在 5 个文件里的 dp() / dp2() 私有助手，以及若干处 raw px padding。
 *
 * 设备 density = 2.0（320dpi 报告值）。真实物理 dpi≈220，1dp≈0.229mm。
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    public static float dpF(Context c, int v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** 触控目标最小边长（dp）。系统实测：Music 顶带 76×76px（≈38dp 报告值），Settings 行 100×100px。 */
    public static final int TOUCH_MIN_DP = 44;

    /** 主字号梯度（sp）。 */
    public static final int TEXT_XL = 26;   // 卦名大字
    public static final int TEXT_LG = 19;   // 对话框标题/页面主标题
    public static final int TEXT_MD = 17;    // 设置页各分类标题
    public static final int TEXT_BASE = 16; // 列表行/文本查看
    public static final int TEXT_SM = 14;    // 提示/副文本
    public static final int TEXT_XS = 12;    // 分组标签/checkbox
}