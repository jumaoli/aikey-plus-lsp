package io.github.jumaoli.aikey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 动作表自检（纯 JVM，只调用不依赖 Android 的方法）：
 *  - id 不重复
 *  - cmd 为 null 的一定是 shell 里实现了的内置动作
 *  - %D% 只出现在 _back 变体里，且能被替换
 */
public final class ActionCatalogTest {

    private static int failed;

    public static void main(String[] args) {
        List<ActionCatalog.Item> all = ActionCatalog.items(null);
        check("动作数量 >= 30", all.size() >= 30);
        System.out.println("动作总数: " + all.size());

        Set<String> ids = new HashSet<>();
        for (ActionCatalog.Item it : all) {
            if (!ids.add(it.id)) {
                fail("重复 id: " + it.id);
            }
        }
        check("id 唯一", true);

        Set<String> builtins = new HashSet<>();
        builtins.add("none");
        builtins.add("flashlight");
        builtins.add("torch_on");
        builtins.add("torch_off");
        builtins.add("screenshot");
        builtins.add("record");
        builtins.add("record_start");
        builtins.add("record_stop");
        builtins.add("home");
        builtins.add("game_mode");
        builtins.add("app:");
        builtins.add("appback:");
        builtins.add("backhome");
        builtins.add("widget");
        builtins.add("widget_off");
        builtins.add("shell:");

        for (ActionCatalog.Item it : all) {
            if (it.cmd == null) {
                boolean nativeAction = it.id.startsWith("native:");
                if (!nativeAction && !builtins.contains(it.id)) {
                    fail("没有命令也不是内置动作: " + it.id);
                }
            } else if (it.cmd.contains("%D%") && !it.id.endsWith("_back")) {
                fail("%D% 只该出现在 _back: " + it.id);
            }
        }
        check("内置/命令分类正确", true);

        // 背屏变体替换
        for (ActionCatalog.Item it : all) {
            String c = ActionCatalog.command(it.id, 3);
            if (c != null && c.contains("%D%")) {
                fail("占位符没替换: " + it.id);
            }
            if (c != null && it.id.endsWith("_back") && !c.contains("--display 3")) {
                fail("背屏命令缺少 --display: " + it.id + " -> " + c);
            }
        }
        check("背屏 display 替换正确", true);

        // 分组
        for (String g : ActionCatalog.groups()) {
            check("分组非空: " + g, !ActionCatalog.items(g).isEmpty());
        }

        // 关键动作存在
        String[] need = {
                "native:launch_voice_assistant",
                "native:launch_ai_memory",
                "native:launch_alipay_payment_code",
                "native:launch_wechat_payment_code",
                "native:launch_alipay_bus_code",
                "native:launch_wechat_scanner",
                "native:mute",
                "native:launch_peep_protection",
                "native:screenshot_rear",
                "native:launch_camera",
                "alipay_code_rear",
                "alipay_bus_rear",
                "wechat_code",
                "wechat_scan",
        };
        for (String id : need) {
            check("存在 " + id, ActionCatalog.find(id) != null);
        }

        System.out.println();
        System.out.println("failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean cond) {
        if (!cond) {
            fail(name);
        } else {
            System.out.println("ok    " + name);
        }
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("FAIL  " + msg);
    }
}
