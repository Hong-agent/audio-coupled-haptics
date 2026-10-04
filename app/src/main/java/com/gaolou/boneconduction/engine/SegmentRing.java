package com.gaolou.boneconduction.engine;

import android.util.Log;

import java.io.File;

import com.gaolou.boneconduction.core.EngineConfig;

/**
 * 环形文件槽（规格书 §2.1 SegmentRing）：段序号对槽位数取模，复用固定文件。
 */
public final class SegmentRing {

    private static final String TAG = "SegmentRing";

    private final File dir;
    private final File[] slots = new File[EngineConfig.RING_SLOTS];

    public SegmentRing(File dir) {
        this.dir = dir;
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create segment dir: " + dir);
        }
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new File(dir, String.format("seg_%06d.ogg", i));
        }
    }

    public File fileFor(long seq) {
        return slots[(int) Math.floorMod(seq, slots.length)];
    }

    public File dir() {
        return dir;
    }

    public void clean() {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.getName().startsWith("seg_") && !f.delete()) {
                Log.w(TAG, "cannot delete " + f);
            }
        }
    }
}
