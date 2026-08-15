package com.magneo.compass.ui;

/**
 * 圆屏（800×800，物理直径 92mm，density 320）统一几何工具。
 *
 * 物理参数（实测，见 设备说明.md）：
 *   - 可见半径 R = 400px = 46mm
 *   - 系统报告 density=2.0（320dpi），但真实物理 dpi ≈ 220（1px≈0.115mm）
 *   - 由此 1dp = 2px ≈ 0.229mm 物理上比"标准 Android"放大约 1.45×
 *   - 四角裁切深度 = R·(√2−1) ≈ 166px ≈ 19mm
 *
 * 系统 app 范式（com.android.music / com.android.settings 实测 bounds）：
 *   - Music：所有元素严格围绕 x=400 中轴对称，按 y 分顶/中/底三带，每带只放居中主键
 *   - Settings：标题居中，列表靠物理圆玻璃自然裁，不套 oval mask
 *   - safeHalfWidthAt(y) 是这两套共用的核心算子
 */
public final class RoundScreen {

    private RoundScreen() {}

    /** 设计基准：CompassView 的 800px 设计图缩放因子。 */
    public static float scale800(float w, float h) {
        return Math.min(w, h) / 800f;
    }

    /** 可见圆半径 R。 */
    public static float R(float w, float h) {
        return Math.min(w, h) / 2f;
    }

    /** 圆心 X。 */
    public static float cx(float w) { return w / 2f; }

    /** 圆心 Y。 */
    public static float cy(float h) { return h / 2f; }

    /**
     * 在 y 处圆内的可用水平半宽（px）。
     * Music/Settings 范式的核心算子：顶/底带窄，中带宽。
     * Δy 超出 R 时返回 0（无可用宽度）。
     */
    public static float safeHalfWidthAt(float w, float h, float y) {
        float r = R(w, h);
        float dy = y - cy(h);
        if (Math.abs(dy) >= r) return 0f;
        return (float) Math.sqrt(r * r - dy * dy);
    }

    /**
     * 环上某角度 cell 的最大半边长（px），保证 cell 四角不出 R。
     *
     * 推导：cell 中心位于 (cx + r·cos α, cy + r·sin α)，cell 半边长 c。
     * 四角最远点到圆心距离 = √((r+c)²cos²α + (r+c)²sin²α) = r+c（仅当 cell 中心
     * 不在轴上时成立不对）。严格判据：要求四个角 (cx ± c, cy ± c) 平移到 (r·cos α, r·sin α)
     * 之后都 ≤ R。即 (r·cos α + c)² + (r·sin α + c)² ≤ R²，且四个象限对称。
     * 解出 c ≤ R − r·max(|cos α|, |sin α|) ... 不对，应是 c ≤ R·(... )。
     *
     * 真正的几何：cell 是轴对齐正方形，半边 c。最远角相对 cell 中心的位移是 (±c, ±c)。
     * cell 中心在 (r cosα, r sinα)。最远角到圆心距离平方：
     *   (r cosα ± c)² + (r sinα ± c)² 取符号使模最大 = r² + 2c² + 2c r (|cosα| + |sinα|)
     * 要 ≤ R²：c 满足 c² + c r (|cosα| + |sinα|) + (r² − R²)/2 ≤ 0
     * 解作 c = [−B − √(B²−4AC)] / 2 取较小的正根。
     * 简化用：c = R − r·max(|cosα|, |sinα|) 是上界估计（保守、不等价，但够用）。
     *
     * @param r         环半径（px，cell 中心到圆心距离）
     * @param angleDeg  cell 中心相对圆心的角度（°，0=右，90=下，符合 Android 坐标）
     * @param R         可见圆半径（px）
     * @return cell 最大半边长（px，正值）；若 r 已 ≥ R 则 0
     */
    public static float maxCellHalf(float r, float angleDeg, float R) {
        if (r <= 0 || r >= R) return 0f;
        double a = Math.toRadians(angleDeg);
        double ac = Math.abs(Math.cos(a));
        double as = Math.abs(Math.sin(a));
        // 严格解：c² + c·r·(ac+as) − (R²−r²)/2 ≤ 0，取较小正根
        double B = r * (ac + as);
        double C = -(R * R - r * r) / 2.0;
        double disc = B * B - 4 * C;   // A=1, C<0 故恒正
        double c = (-B + Math.sqrt(disc)) / 2.0;   // 取 +√ 得正根
        return (float) c;
    }

    /** 在 y 处给容器内左右内边距（px），让中部内容不出圆。 */
    public static int safeInsetPx(float w, float h, float y, int extraDp, float density) {
        float half = safeHalfWidthAt(w, h, y);
        int extra = (int) (extraDp * density);
        int inset = (int) (w / 2f - half) + extra;
        return Math.max(0, inset);
    }
}