package com.pankscan.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 自动定位（ImageOps.locateBars + cropRect）的对照测试。
 *
 * 比的是 v1.6 解码链的第 2 步和新增的第 2b 步：
 *  - A「现状」：全幅 → 固定扫描带（0.15~0.85 / 0.30~0.62，三档偏置）
 *  - B「新链」：A 再叠一个「行梯度 + 列梯度」自动定位出来的框
 *
 * 两个场景：
 *  1) 合成：条码纵向位置从 0.18 扫到 0.82，看固定带落空时定位能不能接住；
 *  2) 实拍：现场照片走一遍新链路，确认定位框裁出来还能解出原 SN。
 *
 * 用法见 改进方案_v1.6.md 第五节。
 */
public class LocateTest {

    static final String PHOTO =
            "C:/Users/yuguo/.workbuddy/clipboard-images/clipboard-2026-11-02T13-54-35-018Z-1d5fdbd9.jpg";
    static final String EXPECT = "LXT261102AC0000078";
    static final float[] B3 = {-0.12f, 0f, 0.12f};

    /* ---------------- 解码 ---------------- */

    static MultiFormatReader reader(boolean hard) {
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> f = new ArrayList<>();
        f.add(BarcodeFormat.CODE_128);
        h.put(DecodeHintType.POSSIBLE_FORMATS, f);
        h.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        h.put(DecodeHintType.TRY_HARDER, hard ? Boolean.TRUE : Boolean.FALSE);
        MultiFormatReader r = new MultiFormatReader();
        r.setHints(h);
        return r;
    }

    static String decode(byte[] buf, int w, int h, int bin) {
        if (buf == null || w <= 0 || h <= 0) return null;
        try {
            LuminanceSource src = new PlanarYUVLuminanceSource(buf, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(bin == ImageOps.BIN_GLOBAL
                    ? new GlobalHistogramBinarizer(src) : new HybridBinarizer(src));
            Result res = reader(true).decodeWithState(bmp);
            return res == null ? null : res.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 1=读对 2=读错 0=没读出来 */
    static int verdict(String got, String expect) {
        if (got == null) return 0;
        return expect.equals(got) ? 1 : 2;
    }

    /** 锐化 + 指定阈值偏置 + 两种二值化；不改动传进来的缓冲区 */
    static int enhance(byte[] src, int w, int h, float bias, String expect) {
        if (src == null || w < 40 || h < 20) return 0;
        byte[] b = src.clone();
        new ImageOps().sharpen(b, w, h);
        int sh = (int) (bias * 255f);
        if (sh != 0) new ImageOps().shift(b, w * h, sh);
        int v = verdict(decode(b, w, h, ImageOps.BIN_HYBRID), expect);
        if (v != 0) return v;
        return verdict(decode(b, w, h, ImageOps.BIN_GLOBAL), expect);
    }

    static int band(byte[] up, int w, int h, float l, float t, float r, float b,
                    float bias, String expect) {
        ImageOps ops = new ImageOps();
        byte[] box = ops.cropUpscale(up, w, h, l, t, r, b, 1);
        return enhance(box, ops.outWidth(), ops.outHeight(), bias, expect);
    }

    static int located(byte[] up, int w, int h, float bias, String expect) {
        ImageOps ops = new ImageOps();
        int[] box = new int[4];
        if (!ops.locateBars(up, w, h, box)) return 0;
        byte[] crop = ops.cropRect(up, w, h, box[0], box[1], box[2], box[3], 1);
        return enhance(crop, ops.outWidth(), ops.outHeight(), bias, expect);
    }

    /** 三档偏置全试（等于「连续三帧，每帧换一档」） */
    static int any3(Chain c, byte[] up, int w, int h, String expect) {
        int wrong = 0;
        for (float bias : B3) {
            int v = c.run(up, w, h, bias, expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
        }
        return wrong;
    }

    interface Chain {
        int run(byte[] up, int w, int h, float bias, String expect);
    }

    static final Chain A = new Chain() {
        public int run(byte[] up, int w, int h, float bias, String expect) {
            int v = verdict(decode(up, w, h, ImageOps.BIN_HYBRID), expect);
            if (v != 0) return v;
            return band(up, w, h, 0.15f, 0.30f, 0.85f, 0.62f, bias, expect);
        }
    };

    static final Chain B = new Chain() {
        public int run(byte[] up, int w, int h, float bias, String expect) {
            int v = A.run(up, w, h, bias, expect);
            if (v != 0) return v;
            return located(up, w, h, bias, expect);
        }
    };

    /* ---------------- 场景 1：条码纵向位置 ---------------- */

    static void synthetic() {
        String code = "LXT261027AC0000030";
        float[] centers = {0.18f, 0.22f, 0.34f, 0.46f, 0.60f, 0.72f, 0.82f};
        float[] mods = {1.5f, 1.75f, 2.0f, 2.25f};
        int[] blurs = {1, 2};
        int phases = 6;

        System.out.println("=== 合成：条码纵向位置 × 模块宽 × 模糊（每格 读对/读错/总） ===");
        System.out.printf("%-6s %-4s %-6s %-12s %-12s%n", "中心", "模糊", "模块", "A现状", "B新链");
        int[] sa = {0}, sb = {0}, wa = {0}, wb = {0}, tot = {0};
        for (float cy : centers) {
            int[] ca = {0}, cb = {0}, xa = {0}, xb = {0}, n = {0};
            for (int passes : blurs) {
                for (float m : mods) {
                    for (int ph = 0; ph < phases; ph++) {
                        byte[] cam = GainProbe.render(code, m, ph / (float) phases, passes,
                                0.55f, cy, 0f, 3301L * passes + (long) (m * 100) + ph + (long) (cy * 100));
                        if (cam == null) continue;
                        ImageOps ops = new ImageOps();
                        byte[] up = ops.rotate(cam, GainProbe.CAM_W, GainProbe.CAM_H, true, 0);
                        n[0]++;
                        int va = any3(A, up, GainProbe.UP_W, GainProbe.UP_H, code);
                        if (va == 1) ca[0]++;
                        if (va == 2) xa[0]++;
                        int vb = any3(B, up, GainProbe.UP_W, GainProbe.UP_H, code);
                        if (vb == 1) cb[0]++;
                        if (vb == 2) xb[0]++;
                    }
                }
            }
            if (n[0] == 0) continue;
            System.out.printf("%-6.2f %-4s %-6s %-12s %-12s%n", cy, "-", "-",
                    ca[0] + "/" + xa[0] + "/" + n[0], cb[0] + "/" + xb[0] + "/" + n[0]);
            sa[0] += ca[0];
            sb[0] += cb[0];
            wa[0] += xa[0];
            wb[0] += xb[0];
            tot[0] += n[0];
        }
        System.out.println("-----------------------------------------------------------");
        System.out.printf("%-17s %-12s %-12s%n", "合计/" + tot[0],
                sa[0] + "/" + wa[0] + "/" + tot[0], sb[0] + "/" + wb[0] + "/" + tot[0]);
        System.out.printf("%-17s %-12s %-12s%n", "识别率",
                pct(sa[0], tot[0]), pct(sb[0], tot[0]));
    }

    static String pct(int ok, int n) {
        return n == 0 ? "-" : String.format("%.0f%%", 100.0 * ok / n);
    }

    /* ---------------- 场景 2：实拍照片 ---------------- */

    static void photo() throws Exception {
        BufferedImage im = ImageIO.read(new File(PHOTO));
        int w = im.getWidth();
        int h = im.getHeight();
        byte[] g = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = im.getRGB(x, y);
                int r = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, b = p & 0xFF;
                g[y * w + x] = (byte) ((r * 77 + gg * 151 + b * 28) >> 8);
            }
        }
        System.out.println();
        System.out.println("=== 实拍：" + w + "x" + h + " ===");

        ImageOps ops = new ImageOps();
        int[] box = new int[4];
        long t0 = System.nanoTime();
        boolean ok = ops.locateBars(g, w, h, box);
        long us = (System.nanoTime() - t0) / 1000;
        if (!ok) {
            System.out.println("定位失败（照片里 5 张标签叠在一起，理论上不该失败）");
            return;
        }
        System.out.printf("定位框 x %d~%d  y %d~%d  → %d x %d，耗时 %d µs%n",
                box[0], box[2], box[1], box[3], box[2] - box[0], box[3] - box[1], us);

        byte[] crop = ops.cropRect(g, w, h, box[0], box[1], box[2], box[3], 1);
        int cw = ops.outWidth();
        int ch = ops.outHeight();
        for (float bias : B3) {
            int v = enhance(crop, cw, ch, bias, EXPECT);
            System.out.printf("  偏置 %+.2f → %s%n", bias,
                    v == 1 ? "读对 " + EXPECT : (v == 2 ? "读错" : "没读出来"));
        }
    }

    public static void main(String[] args) throws Exception {
        synthetic();
        photo();
    }
}
