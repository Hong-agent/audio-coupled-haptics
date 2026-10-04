package com.gaolou.boneconduction.engine;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 特权 shell 封装（规格书 §8.3）：静音外部原声需要读 {@code dumpsys audio}。
 * 探测不到特权时优雅降级（会有回声，属预期）。
 */
public final class PrivilegedShell {

    private static final String TAG = "PrivilegedShell";

    public enum Backend {
        NONE, SU, SHIZUKU
    }

    public static final class Result {
        public final int code;
        public final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    private volatile Backend backend = Backend.NONE;

    public Backend backend() {
        return backend;
    }

    public String backendName() {
        switch (backend) {
            case SU:
                return "su（root）";
            case SHIZUKU:
                return "shizuku / adb shell";
            default:
                return "不可用（静音降级）";
        }
    }

    public void detect() {
        Result su = exec(new String[]{"su", "-c", "id"}, 1500);
        if (su.ok() && su.output.contains("uid=0")) {
            backend = Backend.SU;
            Log.i(TAG, "privileged backend: su");
            return;
        }
        Result sh = exec(new String[]{"sh", "-c", "dumpsys audio | head -n 1"}, 1500);
        if (sh.ok() && sh.output.contains("Audio")) {
            backend = Backend.SHIZUKU;
            Log.i(TAG, "privileged backend: shizuku/shell");
            return;
        }
        backend = Backend.NONE;
        Log.i(TAG, "no privileged shell available");
    }

    /** 执行特权命令；无特权时返回失败结果。 */
    public Result execPrivileged(String command) {
        switch (backend) {
            case SU:
                return exec(new String[]{"su", "-c", command}, 3000);
            case SHIZUKU:
                return exec(new String[]{"sh", "-c", command}, 3000);
            default:
                return new Result(-1, "");
        }
    }

    public static Result exec(String[] argv, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy();
                return new Result(-2, sb.toString());
            }
            return new Result(process.exitValue(), sb.toString());
        } catch (Exception e) {
            return new Result(-3, e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
