package io.github.jumaoli.aikey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收系统「设置 → AI 键」页面里用户的选择。
 *
 * 系统会把非白名单的 click_mi_ai_key 立刻改回 none，所以不能依赖它持久化：
 * 我们的注入器在页面暂停时把两个下拉框的当前值广播过来，这里直接写进本模块配置。
 */
public class SyncReceiver extends BroadcastReceiver {

    public static final String ACTION = "io.github.jumaoli.aikey.SYNC_SELECTION";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || !ACTION.equals(intent.getAction())) {
                return;
            }
            String click = intent.getStringExtra("click");
            String longPress = intent.getStringExtra("long");
            Engine.get(context.getApplicationContext()).applySystemSelection(click, longPress);
        } catch (Throwable t) {
            Logs.append(context, "同步系统选择失败: " + t);
        }
    }
}
