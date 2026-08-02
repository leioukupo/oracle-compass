package com.magneo.compass;

import android.app.Activity;
import android.util.Log;

import java.lang.reflect.Field;

/** 绕过 ROM 的“防误退”（checkAllowQuitState/mIsAllowQuit）：反射把字段置 true。 */
public class QuitFix {
    private static final String TAG = "QuitFix";

    public static void apply(Activity a) {
        try {
            for (Field f : Activity.class.getDeclaredFields()) {
                if (f.getName().toLowerCase().contains("quit")) {
                    Log.d(TAG, "field=" + f.getName() + " type=" + f.getType().getSimpleName());
                    f.setAccessible(true);
                    Object v = f.get(a);
                    Log.d(TAG, "  value=" + v);
                    if (f.getType() == boolean.class && Boolean.FALSE.equals(v)) {
                        f.setBoolean(a, true);
                        Log.d(TAG, "  -> 已改为 true");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
        }
    }
}
