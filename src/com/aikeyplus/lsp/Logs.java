package com.aikeyplus.lsp;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** App 侧日志：钩子、动作分发、root 执行结果都汇总到这里，界面里直接看。 */
public final class Logs {

    private static final long MAX_BYTES = 96 * 1024;

    private Logs() {
    }

    public static File file(Context c) {
        return new File(Engine.dir(c), "app.log");
    }

    public static synchronized void append(Context c, String msg) {
        try {
            File f = file(c);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (f.length() > MAX_BYTES) {
                String tail = tail(c, 300);
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(tail.getBytes("UTF-8"));
                fos.close();
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " " + msg + "\n";
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized String tail(Context c, int lines) {
        try {
            File f = file(c);
            if (!f.exists()) {
                return "";
            }
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 64 * 1024);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String[] all = new String(buf, "UTF-8").split("\n");
            int from = Math.max(0, all.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.length; i++) {
                sb.append(all[i]).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(读取日志失败: " + t + ")";
        }
    }
}
