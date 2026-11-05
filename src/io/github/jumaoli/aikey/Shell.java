package io.github.jumaoli.aikey;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 常驻 root shell：装好 su 后一直复用同一个 root 进程，
 * 每次动作只写一行命令进去，延迟比"每次 su -c"低一个数量级。
 */
public final class Shell {

    public static final class Result {
        public final boolean ok;
        public final String out;
        public final int code;

        Result(boolean ok, String out, int code) {
            this.ok = ok;
            this.out = out == null ? "" : out;
            this.code = code;
        }
    }

    /** 0=未知 1=已授权 -1=未授权 */
    public static volatile int rootState = 0;

    private static volatile long lastProbe;

    private static final Object LOCK = new Object();
    private static Process proc;
    private static BufferedWriter stdin;
    private static LinkedBlockingQueue<String> queue;
    private static int seq;
    private static final String EOF = "__AIKEY_EOF__";

    private Shell() {
    }

    private static boolean alive() {
        return proc != null && proc.isAlive() && stdin != null && queue != null;
    }

    private static void kill() {
        try {
            if (proc != null) {
                proc.destroy();
            }
        } catch (Throwable ignored) {
        }
        proc = null;
        stdin = null;
        queue = null;
    }

    /** 拉起 root shell 并验证 uid=0。 */
    private static boolean start() {
        kill();
        try {
            ProcessBuilder pb = new ProcessBuilder("su");
            pb.redirectErrorStream(true);
            proc = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
            final LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
            queue = q;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            q.add(line);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        q.add(EOF);
                    }
                }
            }, "aikey-su-reader");
            t.setDaemon(true);
            t.start();

            String tag = "AIKEY_CHECK_" + (++seq);
            stdin.write("id -u\n");
            stdin.write("echo " + tag + ":$?\n");
            stdin.flush();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + 6000;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    kill();
                    rootState = -1;
                    return false;
                }
                String line = q.poll(left, TimeUnit.MILLISECONDS);
                if (line == null || EOF.equals(line)) {
                    kill();
                    rootState = -1;
                    return false;
                }
                if (line.startsWith(tag + ":")) {
                    break;
                }
                sb.append(line).append('\n');
            }
            boolean ok = sb.toString().trim().contains("0");
            rootState = ok ? 1 : -1;
            if (!ok) {
                kill();
                return false;
            }
            return true;
        } catch (Throwable t) {
            kill();
            rootState = -1;
            return false;
        }
    }

    /** 执行一条命令，返回输出。命令里不要带换行。 */
    public static Result run(String cmd, long timeoutMs) {
        synchronized (LOCK) {
            if (!alive() && !start()) {
                String why = "su 不可用（未授权 root？）";
                return new Result(false, why, -1);
            }
            String tag = "AIKEY_END_" + (++seq);
            try {
                stdin.write(cmd + "\n");
                stdin.write("echo " + tag + ":$?\n");
                stdin.flush();
            } catch (Throwable t) {
                kill();
                return new Result(false, "root shell 写入失败: " + t, -1);
            }
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    kill();
                    return new Result(false, "执行超时: " + cmd, -1);
                }
                String line;
                try {
                    line = queue.poll(left, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result(false, "被中断", -1);
                }
                if (line == null) {
                    kill();
                    return new Result(false, "执行超时: " + cmd, -1);
                }
                if (EOF.equals(line)) {
                    kill();
                    return new Result(false, "root shell 已退出", -1);
                }
                if (line.startsWith(tag + ":")) {
                    int code;
                    try {
                        code = Integer.parseInt(line.substring(tag.length() + 1).trim());
                    } catch (Throwable t) {
                        code = 0;
                    }
                    return new Result(code == 0, sb.toString(), code);
                }
                sb.append(line).append('\n');
            }
        }
    }

    public static boolean hasRoot() {
        return hasRoot(false);
    }

    /** force=true 时忽略节流，立刻重新问一次 su（用户刚在管理器里授权时用）。 */
    public static boolean hasRoot(boolean force) {
        if (rootState == 1 && alive()) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastProbe < 10000) {
            return rootState == 1;
        }
        lastProbe = now;
        Result r = run("id -u", 8000);
        boolean ok = r.ok && r.out.trim().startsWith("0");
        rootState = ok ? 1 : -1;
        return ok;
    }

    public static void shutdown() {
        synchronized (LOCK) {
            try {
                if (stdin != null) {
                    stdin.write("exit\n");
                    stdin.flush();
                }
            } catch (Throwable ignored) {
            }
            kill();
        }
    }
}
