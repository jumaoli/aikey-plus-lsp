package io.github.jumaoli.aikey;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

/**
 * 手电筒：用 CameraManager.setTorchMode。
 *
 * 为什么不用 sysfs：这台机器 /sys/class/leds 下的闪光灯节点连 root 都写不了
 * （SELinux 拒绝），之前那套节点写入必然失败。
 * CameraManager 走的是相机 HAL，稳定且能读回状态。
 *
 * 需要 CAMERA 权限；有 root 时由 App 自动 pm grant。
 */
public final class Torch {

    private static String cachedId;
    private static boolean state;

    private Torch() {
    }

    public static synchronized boolean toggle(Context c) {
        return set(c, !state);
    }

    public static synchronized boolean set(Context c, boolean on) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                return false;
            }
            String id = cachedId;
            if (id == null) {
                for (String candidate : cm.getCameraIdList()) {
                    Boolean flash = cm.getCameraCharacteristics(candidate)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) {
                        id = candidate;
                        break;
                    }
                }
            }
            if (id == null) {
                Logs.append(c, "没有带闪光灯的相机");
                return false;
            }
            cm.setTorchMode(id, on);
            cachedId = id;
            state = on;
            Logs.append(c, "手电筒 -> " + (on ? "开" : "关"));
            return true;
        } catch (Throwable t) {
            Logs.append(c, "手电筒失败: " + t);
            return false;
        }
    }

    public static synchronized boolean isOn() {
        return state;
    }
}
