package com.magneo.compass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** 触发系统“选择默认桌面”选择器的辅助 Activity。 */
public class FakeHomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
    }
}
