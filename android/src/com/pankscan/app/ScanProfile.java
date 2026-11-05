package com.pankscan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

/**
 * 本机识别画像。
 *
 * 「学习」在这里是一件很具体的事，不是让算法自己改自己（那样识别率会漂移，现场不敢用），
 * 而是让**每台手机记住自己身上的统计规律**：
 *
 *  1. 解码链四段（快速通道 / 固定扫描带 / 自动定位 / 全幅兜底）哪一段最常出结果；
 *  2. 三档阈值偏置里哪一档最容易读出来；
 *  3. 这台机器的预览尺寸、变焦档位、平均出码耗时；
 *  4. 从已经入账的记录里学到 SN 的长度与前缀，用来收紧误读白名单。
 *
 * 前三条会被反馈到解码顺序上：如果这台机器上「自动定位」远比「固定扫描带」灵，
 * 下次就把它提到前面；某档偏置命中率明显更高，就多给它上场机会。
 * 数据只写在本机 SharedPreferences 里，换手机不共享——这正好是想要的行为。
 */
class ScanProfile {

    private static final String PREF = "scan_profile_v1";

    private static final String K_FRAMES = "frames";
    private static final String K_FAST = "fast";
    private static final String K_BAND = "band";
    private static final String K_LOCATED = "located";
    private static final String K_MULTI = "multi";
    private static final String K_B0 = "b0";
    private static final String K_B1 = "b1";
    private static final String K_B2 = "b2";
    private static final String K_MS = "msSum";
    private static final String K_MSN = "msN";
    private static final String K_DEVICE = "device";
    private static final String K_PREVIEW = "preview";
    private static final String K_ZOOM = "zoom";
    private static final String K_SN_PREFIX = "snPrefix";
    private static final String K_SN_LEN = "snLen";

    /** 学习到的样本数低于这个值时，一切按默认规则走，不做任何调整 */
    private static final int MIN_EVIDENCE = 24;

    private final SharedPreferences sp;

    private int frames, fast, band, located, multi;
    private int b0, b1, b2;
    private int msSum, msN;

    private String device = "";
    private String preview = "";
    private String zoom = "1×";
    private String snPrefix = "";
    private int snLen;

    ScanProfile(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        frames = sp.getInt(K_FRAMES, 0);
        fast = sp.getInt(K_FAST, 0);
        band = sp.getInt(K_BAND, 0);
        located = sp.getInt(K_LOCATED, 0);
        multi = sp.getInt(K_MULTI, 0);
        b0 = sp.getInt(K_B0, 0);
        b1 = sp.getInt(K_B1, 0);
        b2 = sp.getInt(K_B2, 0);
        msSum = sp.getInt(K_MS, 0);
        msN = sp.getInt(K_MSN, 0);
        device = sp.getString(K_DEVICE, "");
        preview = sp.getString(K_PREVIEW, "");
        zoom = sp.getString(K_ZOOM, "1×");
        snPrefix = sp.getString(K_SN_PREFIX, "");
        snLen = sp.getInt(K_SN_LEN, 0);
        if (device.length() == 0) device = Build.MANUFACTURER + " " + Build.MODEL;
    }

    /* ---------------- 采集 ---------------- */

    void setPreview(String v) {
        if (v != null && v.length() > 0) preview = v;
    }

    void setZoom(String v) {
        if (v != null && v.length() > 0) zoom = v;
    }

    void tickFrame() {
        frames++;
        if (frames > 1_000_000) frames = 1_000_000;
    }

    void tickMs(int ms) {
        if (ms <= 0 || ms > 5000) return;
        msSum += ms;
        msN++;
        if (msN > 100000) {
            msSum /= 2;
            msN /= 2;
        }
    }

    /** kind: 0 快速通道 / 1 固定扫描带 / 2 自动定位 / 3 全幅兜底 */
    void hit(int kind) {
        switch (kind) {
            case 0:
                fast++;
                break;
            case 1:
                band++;
                break;
            case 2:
                located++;
                break;
            default:
                multi++;
                break;
        }
    }

    void hitBias(int idx) {
        if (idx == 0) b0++;
        else if (idx == 1) b1++;
        else if (idx == 2) b2++;
    }

    /* ---------------- 反馈到解码顺序 ---------------- */

    /**
     * 这一帧该用哪一档阈值偏置。
     *
     * 证据不足就老老实实 0/1/2 轮换；攒够了再看哪档命中最多：
     * 偶数帧押最优档，奇数帧继续轮换——不能全押，否则万一分布变了就再也纠不回来。
     */
    int pickBias(long seq) {
        int tot = b0 + b1 + b2;
        if (tot < MIN_EVIDENCE) return (int) (seq % 3);
        int best = 0;
        if (b1 > b0 && b1 >= b2) best = 1;
        else if (b2 > b0 && b2 > b1) best = 2;
        return (seq % 2 == 0) ? best : (int) (seq % 3);
    }

    /** 「自动定位」是不是该排到「固定扫描带」前面 */
    boolean preferLocated() {
        if (band + located < MIN_EVIDENCE) return false;
        return located >= band * 2 && located >= 8;
    }

    /* ---------------- SN 形态 ---------------- */

    /** 由网页侧从已入账记录里统计出来，能学到就学，学不到保持默认规则 */
    void setSnHint(String prefix, int len) {
        prefix = prefix == null ? "" : prefix.trim().toUpperCase();
        if (prefix.length() < 2 || prefix.length() > 4) return;
        if (len < 8 || len > 40) return;
        snPrefix = prefix;
        snLen = len;
    }

    /** 现场 SN 的默认形态：LXT + 6 位数字 + AC + 7 位数字 = 18 位 */
    private static boolean defaultSn(String s) {
        if (s == null || s.length() != 18) return false;
        if (!s.startsWith("LXT")) return false;
        if (s.charAt(9) != 'A' || s.charAt(10) != 'C') return false;
        return upperAlnum(s);
    }

    private static boolean upperAlnum(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return true;
    }

    /**
     * 这条文本像不像现场 SN。学会了前缀/长度就按学到的判，同时保留默认形态
     * ——学到的规则更松，两者取或，绝不会把原本能认的码挡在外面。
     */
    boolean snOk(String s) {
        if (s == null) return false;
        if (defaultSn(s)) return true;
        if (snLen > 0 && s.length() == snLen && s.startsWith(snPrefix)) {
            return upperAlnum(s);
        }
        return false;
    }

    /* ---------------- 持久化 ---------------- */

    void flush() {
        try {
            sp.edit()
                    .putInt(K_FRAMES, frames)
                    .putInt(K_FAST, fast)
                    .putInt(K_BAND, band)
                    .putInt(K_LOCATED, located)
                    .putInt(K_MULTI, multi)
                    .putInt(K_B0, b0)
                    .putInt(K_B1, b1)
                    .putInt(K_B2, b2)
                    .putInt(K_MS, msSum)
                    .putInt(K_MSN, msN)
                    .putString(K_DEVICE, device)
                    .putString(K_PREVIEW, preview)
                    .putString(K_ZOOM, zoom)
                    .putString(K_SN_PREFIX, snPrefix)
                    .putInt(K_SN_LEN, snLen)
                    .apply();
        } catch (Throwable ignore) {
        }
    }

    void reset() {
        frames = fast = band = located = multi = 0;
        b0 = b1 = b2 = 0;
        msSum = msN = 0;
        snPrefix = "";
        snLen = 0;
        flush();
    }

    String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("device", device);
            o.put("preview", preview.length() == 0 ? "-" : preview);
            o.put("zoom", zoom);
            o.put("frames", frames);
            o.put("fast", fast);
            o.put("band", band);
            o.put("located", located);
            o.put("multi", multi);
            o.put("avgMs", msN > 0 ? (msSum / msN) : 0);
            o.put("snPrefix", snPrefix);
            o.put("snLen", snLen);
            o.put("preferLocated", preferLocated());
        } catch (Throwable ignore) {
        }
        return o.toString();
    }
}
