package io.github.jumaoli.aikey.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

import io.github.jumaoli.aikey.ActionCatalog;

/**
 * 在 system_server 里直接执行动作，不绕回 App。
 *
 * 大多数动作本质就是一条 am start（组件或 URL），这里直接把命令解析成 Intent 用
 * 系统身份发出去，耗时和 MIUI 自己调动作基本一致。
 * 解析不了或需要 root 的命令返回 false，交给上层回退给 App。
 */
public final class DirectRunner {

    private DirectRunner() {
    }

    public static boolean execute(Context ctx, String action, int backDisplay) {
        if (ctx == null || action == null || action.isEmpty()) {
            return false;
        }
        try {
            if (action.startsWith("native:")) {
                return NativeActions.trigger(ctx, action.substring(7));
            }
            if (action.startsWith("app:")) {
                return launchApp(ctx, action.substring(4));
            }
            if (action.startsWith("shell:")) {
                return runAmStart(ctx, action.substring(6), backDisplay);
            }
            String cmd = ActionCatalog.command(action, backDisplay);
            if (cmd == null) {
                return false;
            }
            return runAmStart(ctx, cmd, backDisplay);
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 直接执行失败 " + action + ": " + t);
            return false;
        }
    }

    private static boolean launchApp(Context ctx, String pkg) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析 am start 命令并直接发出 Intent。 */
    private static boolean runAmStart(Context ctx, String cmd, int backDisplay) {
        String s = cmd.trim();
        if (!s.startsWith("am start")) {
            return false;
        }
        List<String> args = split(s);
        Intent intent = new Intent();
        int i = 2; // 跳过 am start
        boolean hasComponent = false;
        boolean hasAction = false;
        while (i < args.size()) {
            String a = args.get(i);
            switch (a) {
                case "-n": {
                    if (i + 1 >= args.size()) {
                        return false;
                    }
                    ComponentName cn = ComponentName.unflattenFromString(args.get(++i));
                    if (cn == null) {
                        return false;
                    }
                    intent.setComponent(cn);
                    hasComponent = true;
                    break;
                }
                case "-a": {
                    if (i + 1 >= args.size()) {
                        return false;
                    }
                    intent.setAction(args.get(++i));
                    hasAction = true;
                    break;
                }
                case "-d": {
                    if (i + 1 >= args.size()) {
                        return false;
                    }
                    intent.setData(Uri.parse(args.get(++i)));
                    break;
                }
                case "-t": {
                    if (i + 1 >= args.size()) {
                        return false;
                    }
                    intent.setType(args.get(++i));
                    break;
                }
                case "-f": {
                    if (i + 1 >= args.size()) {
                        return false;
                    }
                    intent.addFlags(parseInt(args.get(++i), 0));
                    break;
                }
                case "--ei":
                case "--es":
                case "--ez":
                case "--el": {
                    if (i + 2 >= args.size()) {
                        return false;
                    }
                    String key = args.get(++i);
                    String val = args.get(++i);
                    if ("--ei".equals(a)) {
                        intent.putExtra(key, parseInt(val, 0));
                    } else if ("--ez".equals(a)) {
                        intent.putExtra(key, "true".equalsIgnoreCase(val));
                    } else if ("--el".equals(a)) {
                        intent.putExtra(key, parseLong(val, 0));
                    } else {
                        intent.putExtra(key, val);
                    }
                    break;
                }
                case "--display": {
                    // 指定屏幕（背屏）交给 App 处理，那里有 ActivityOptions 的兼容处理
                    return false;
                }
                default:
                    // 其它未知参数（如 --user / --activity-*）一律放弃，走 App 回退
                    if (a.startsWith("-")) {
                        return false;
                    }
                    break;
            }
            i++;
        }
        if (!hasComponent && !hasAction) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        XposedBridge.log("AIKey: 直接启动 " + (hasComponent ? intent.getComponent() : intent.getAction()));
        return true;
    }

    /** 按空格切分，支持单/双引号把带空格的参数合成一个。 */
    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ' ') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
