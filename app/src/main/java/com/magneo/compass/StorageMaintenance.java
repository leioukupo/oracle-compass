package com.magneo.compass;

import android.content.Context;
import android.util.Log;

import java.io.File;

/** Small, conservative cleanup for files that are safe to recreate. */
public final class StorageMaintenance {
    private static final String TAG = "StorageMaintenance";
    private static final long VOICE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long TEMP_RETENTION_MS = 24L * 60L * 60L * 1000L;

    private StorageMaintenance() {}

    public static void cleanup(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            File files = app.getFilesDir();
            deleteIfOld(new File(files, "last_voice.wav"), VOICE_RETENTION_MS);
            deleteIfOld(new File(files, "last_voice.wav.tmp"), TEMP_RETENTION_MS);
            deleteIfOld(new File(files, "conversations.log.tmp"), TEMP_RETENTION_MS);
            deleteIfOld(new File(files, "debug-chain.log.tmp"), TEMP_RETENTION_MS);
            // screenrecord uses these shared-storage files as its rolling input and config
            // buffers. They are safe to recreate and can remain after a forced process kill.
            File shared = android.os.Environment.getExternalStorageDirectory();
            deleteIfOld(new File(shared, "truthscreen.mp4"), TEMP_RETENTION_MS);
            deleteIfOld(new File(shared, "truthcfg.mp4"), TEMP_RETENTION_MS);

            File upload = new File(android.os.Environment.getExternalStorageDirectory(),
                    "Download/oracle-compass/uploads");
            deleteIfOld(new File(upload, "latest.apk.uploading"), TEMP_RETENTION_MS);
            deleteIfOld(new File(upload, "latest.apk.downloading"), TEMP_RETENTION_MS);
        } catch (Throwable t) {
            Log.w(TAG, "cleanup", t);
        }
    }

    private static void deleteIfOld(File file, long retentionMs) {
        try {
            if (!file.exists() || !file.isFile()) return;
            long modified = file.lastModified();
            if (modified <= 0 || System.currentTimeMillis() - modified < retentionMs) return;
            if (file.delete()) Log.i(TAG, "deleted stale file " + file.getName());
        } catch (Exception ignored) {}
    }
}
