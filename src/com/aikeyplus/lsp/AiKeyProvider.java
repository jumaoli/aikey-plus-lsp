package com.aikeyplus.lsp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * 给 system_server 里的 LSPosed 钩子用的桥：
 *   config  —— 取当前配置（手势映射、阈值、键码）
 *   gesture —— 派发一个手势（单击 / 双击 / 长按）
 *   hello   —— 钩子注入成功时上报一次，界面用来显示"已注入"
 *   log     —— 钩子写日志
 *
 * 只接受 system(1000) / root(0) / 自己，避免被别的应用乱调。
 */
public class AiKeyProvider extends ContentProvider {

    public static final String AUTHORITY = "com.aikeyplus.lsp.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    @Override
    public boolean onCreate() {
        return true;
    }

    private boolean allowed() {
        int uid = Binder.getCallingUid();
        return uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!allowed()) {
            return null;
        }
        if (getContext() == null) {
            return null;
        }
        android.content.Context c = getContext();
        Bundle out = new Bundle();
        if ("config".equals(method)) {
            out.putBoolean("enabled", true);
            out.putBoolean("consume", Prefs.boolVal(c, Prefs.K_CONSUME, true));
            out.putBoolean("probe", Prefs.boolVal(c, Prefs.K_PROBE, false));
            out.putBoolean("haptic", Prefs.boolVal(c, Prefs.K_HAPTIC, true));
            out.putString("single", Prefs.action(c, Prefs.G_SINGLE));
            out.putString("double", Prefs.action(c, Prefs.G_DOUBLE));
            out.putString("triple", Prefs.action(c, Prefs.G_TRIPLE));
            out.putString("long", Prefs.action(c, Prefs.G_LONG));
            out.putLong("longMs", Prefs.longMs(c));
            out.putLong("doubleMs", Prefs.doubleMs(c));
            out.putLong("tripleMs", Prefs.tripleMs(c));
            int[] codes = Prefs.keyCodeArray(c);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < codes.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(codes[i]);
            }
            out.putString("codes", sb.toString());
            out.putString("mode", Prefs.mode(c));
            out.putString("version", BuildConfig.VERSION_NAME);
            return out;
        }
        if ("gesture".equals(method)) {
            Engine.get(c).dispatchGesture(arg);
            out.putBoolean("ok", true);
            return out;
        }
        if ("hello".equals(method)) {
            int boot = Prefs.bootCount(c);
            Prefs.setInt(c, Prefs.K_HOOK_BOOT, boot);
            Prefs.setStr(c, Prefs.K_HOOK_INFO, arg == null ? "" : arg);
            Logs.append(c, "LSPosed 钩子已注入：" + arg);
            out.putBoolean("ok", true);
            return out;
        }
        if ("log".equals(method)) {
            Logs.append(c, "[hook] " + arg);
            out.putBoolean("ok", true);
            return out;
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
