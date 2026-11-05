package com.pankscan.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Writer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 低分辨率/低对比度条码的识别率回归测试（不依赖 Android）。
 *
 * 做法：把 Code128 按 1~3 像素的模块宽度渲染（模拟打印分辨率不够），叠加光学模糊、
 * 对比度压缩、噪声和光照渐变；同一个条件下渲染多个「亚像素相位」（真实扫码时条码
 * 和像素栅格不会正好对齐，不同相位成功率差别极大），统计各解码策略的成功帧数。
 *
 * 用法：
 *   javac -source 8 -target 8 -nowarn -encoding UTF-8 -cp android/libs/core.jar \
 *         -d build/test android/src/com/pankscan/app/ImageOps.java android/test/LowResTest.java
 *   java -cp "build/test;android/libs/core.jar" com.pankscan.app.LowResTest
 */
public class LowResTest {

    static final int UP_W = 720;
    static final int UP_H = 1280;
    static final int CAM_W = UP_H;
    static final int CAM_H = UP_W;
    static final int BAR_H = 110;

    static final int PHASES = 8;
    static final float[] MODULES = {1.5f, 1.75f, 2.0f, 2.25f, 2.5f};
    static final int[] BLUR = {1, 2};

    /* ---------------- 模拟印刷 + 光学成像 ---------------- */

    static byte[] renderRow(String code, float modulePx, float phase) {
        BitMatrix bm = new Code128Writer().encode(code, BarcodeFormat.CODE_128, 1, 1);
        int natW = bm.getWidth();
        float start = -phase;
        int outW = (int) Math.ceil(natW * modulePx + 2);
        byte[] row = new byte[outW];
        for (int x = 0; x < outW; x++) {
            float a = (x - start) / modulePx;
            float b = (x + 1 - start) / modulePx;
            float black = 0f;
            int m0 = (int) Math.floor(a);
            int m1 = (int) Math.ceil(b);
            for (int m = m0; m < m1; m++) {
                if (m < 0 || m >= natW) continue;
                if (bm.get(m, 0)) {
                    float s = Math.max(a, m);
                    float e = Math.min(b, m + 1);
                    if (e > s) black += (e - s);
                }
            }
            float cov = black / Math.max(0.0001f, b - a);
            row[x] = (byte) (255 - cov * 255);
        }
        return row;
    }

    static void blur(byte[][] img, int w, int h, int passes) {
        int[] k = {1, 4, 6, 4, 1};
        byte[] tmp = new byte[w * h];
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int s = 0;
                    for (int i = -2; i <= 2; i++) {
                        int xx = x + i;
                        if (xx < 0) xx = 0;
                        if (xx >= w) xx = w - 1;
                        s += (img[0][row + xx] & 0xFF) * k[i + 2];
                    }
                    tmp[row + x] = (byte) (s >> 4);
                }
            }
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int s = 0;
                    for (int i = -2; i <= 2; i++) {
                        int yy = y + i;
                        if (yy < 0) yy = 0;
                        if (yy >= h) yy = h - 1;
                        s += (tmp[yy * w + x] & 0xFF) * k[i + 2];
                    }
                    img[0][row + x] = (byte) (s >> 4);
                }
            }
        }
    }

    /** 返回横放的相机缓冲（1280x720） */
    static byte[] renderFrame(String code, float modulePx, float phase, int passes,
                              float contrast, long seed) {
        Random rnd = new Random(seed);
        byte[] bg = new byte[UP_W * UP_H];
        for (int y = 0; y < UP_H; y++) {
            int base = 228 + (int) (12 * Math.sin(y * Math.PI / UP_H));
            for (int x = 0; x < UP_W; x++) {
                int v = base + rnd.nextInt(7) - 3;
                bg[y * UP_W + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }

        byte[] row = renderRow(code, modulePx, phase);
        int rw = row.length;
        if (rw > UP_W - 20) return null;

        int left = (UP_W - rw) / 2;
        int top = (int) (UP_H * 0.46f) - BAR_H / 2;
        int blackLevel = (int) (255 - 255 * contrast);
        for (int y = 0; y < BAR_H; y++) {
            for (int x = 0; x < rw; x++) {
                int g = row[x] & 0xFF;
                int v = 255 - (int) ((255 - blackLevel) * (255 - g) / 255f);
                v += rnd.nextInt(5) - 2;
                bg[(top + y) * UP_W + left + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }

        byte[][] holder = {bg};
        blur(holder, UP_W, UP_H, passes);
        return new ImageOps().rotate(holder[0], UP_W, UP_H, false, 0);
    }

    /* ---------------- 解码 ---------------- */

    static MultiFormatReader reader(boolean hard) {
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> f = new ArrayList<>();
        f.add(BarcodeFormat.CODE_128);
        f.add(BarcodeFormat.CODE_39);
        f.add(BarcodeFormat.CODE_93);
        f.add(BarcodeFormat.EAN_13);
        f.add(BarcodeFormat.EAN_8);
        f.add(BarcodeFormat.ITF);
        f.add(BarcodeFormat.UPC_A);
        f.add(BarcodeFormat.UPC_E);
        h.put(DecodeHintType.POSSIBLE_FORMATS, f);
        h.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        h.put(DecodeHintType.TRY_HARDER, hard ? Boolean.TRUE : Boolean.FALSE);
        MultiFormatReader r = new MultiFormatReader();
        r.setHints(h);
        return r;
    }

    static String decode(byte[] buf, int w, int h, boolean hard, int bin) {
        if (buf == null || w <= 0 || h <= 0) return null;
        try {
            LuminanceSource src = new PlanarYUVLuminanceSource(buf, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(bin == ImageOps.BIN_GLOBAL
                    ? new GlobalHistogramBinarizer(src) : new HybridBinarizer(src));
            Result res = reader(hard).decodeWithState(bmp);
            return res == null ? null : res.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 策略 ---------------- */

    interface Strategy {
        String name();

        /** 返回 0=失败 1=读对 2=读错（读错最要命，必须为 0） */
        int run(byte[] cam, String expect);
    }

    static int verdict(String got, String expect) {
        if (got == null) return 0;
        return expect.equals(got) ? 1 : 2;
    }

    /** 全幅（旧逻辑） */
    static final Strategy FULL = new Strategy() {
        public String name() {
            return "全幅";
        }

        public int run(byte[] cam, String expect) {
            ImageOps ops = new ImageOps();
            byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
            int v = verdict(decode(up, UP_W, UP_H, false, ImageOps.BIN_HYBRID), expect);
            if (v != 0) return v;
            v = verdict(decode(up, UP_W, UP_H, true, ImageOps.BIN_HYBRID), expect);
            if (v != 0) return v;
            v = verdict(decode(up, UP_W, UP_H, false, ImageOps.BIN_GLOBAL), expect);
            if (v != 0) return v;
            return verdict(decode(up, UP_W, UP_H, true, ImageOps.BIN_GLOBAL), expect);
        }
    };

    /** 全幅 + 某个 ROI 方案 */
    static Strategy withRoi(final String name, final float l, final float t,
                            final float r, final float b, final int scale,
                            final boolean stretch, final int sharpPasses,
                            final float biasFrom, final float biasTo, final int biasSteps) {
        return new Strategy() {
            public String name() {
                return name;
            }

            public int run(byte[] cam, String expect) {
                int base = FULL.run(cam, expect);
                if (base != 0) return base;
                ImageOps ops = new ImageOps();
                byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                byte[] buf = ops.cropUpscale(up, UP_W, UP_H, l, t, r, b, scale);
                if (buf == null) return 0;
                int w = ops.outWidth();
                int h = ops.outHeight();
                if (stretch) ops.stretch(buf, w * h);
                for (int s = 0; s < sharpPasses; s++) ops.sharpen(buf, w, h);
                int wrong = 0;
                for (int i = 0; i < biasSteps; i++) {
                    float bias = (biasSteps <= 1) ? biasFrom
                            : biasFrom + (biasTo - biasFrom) * i / (biasSteps - 1);
                    int shift = (int) (bias * 255f);
                    if (shift != 0) {
                        for (int p = 0; p < w * h; p++) {
                            int v = (buf[p] & 0xFF) + shift;
                            buf[p] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                        }
                    }
                    int v = verdict(decode(buf, w, h, true, ImageOps.BIN_HYBRID), expect);
                    if (v == 1) return 1;
                    if (v == 2) wrong = 2;
                    v = verdict(decode(buf, w, h, true, ImageOps.BIN_GLOBAL), expect);
                    if (v == 1) return 1;
                    if (v == 2) wrong = 2;
                    if (shift != 0) {
                        for (int p = 0; p < w * h; p++) {
                            int x = (buf[p] & 0xFF) - shift;
                            buf[p] = (byte) (x < 0 ? 0 : (x > 255 ? 255 : x));
                        }
                    }
                }
                return wrong;
            }
        };
    }

    public static void main(String[] args) {
        String code = "LXT261027AC0000030";

        List<Strategy> all = new ArrayList<>();
        all.add(FULL);
        all.add(withRoi("单档0", 0.15f, 0.30f, 0.85f, 0.62f, 1, false, 1, 0f, 0f, 1));
        all.add(withRoi("单档-12", 0.15f, 0.30f, 0.85f, 0.62f, 1, false, 1, -0.12f, -0.12f, 1));
        all.add(withRoi("单档+12", 0.15f, 0.30f, 0.85f, 0.62f, 1, false, 1, 0.12f, 0.12f, 1));
        all.add(withRoi("三档轮换", 0.15f, 0.30f, 0.85f, 0.62f, 1, false, 1, -0.12f, 0.12f, 3));

        System.out.println("目标：" + code + "   画幅 " + UP_W + "x" + UP_H + "，条高 " + BAR_H + "px");
        System.out.println("每条件 " + PHASES + " 个亚像素相位；「全幅」= 现有逻辑（Hybrid+Global、easy+hard）");
        System.out.println("单元格为 读对/读错/总数");
        System.out.println();
        System.out.printf("%-7s %-4s", "模块px", "模糊");
        for (Strategy s : all) System.out.printf(" %-11s", s.name());
        System.out.println();
        System.out.println("------------------------------------------------------------------------------------------------");

        int[] sum = new int[all.size()];
        int[] bad = new int[all.size()];
        int frames = 0;
        for (int passes : BLUR) {
            for (float m : MODULES) {
                int[] ok = new int[all.size()];
                int[] wrong = new int[all.size()];
                int n = 0;
                for (int pi = 0; pi < PHASES; pi++) {
                    float ph = pi / (float) PHASES;
                    byte[] cam = renderFrame(code, m, ph, passes, 0.55f,
                            7919L * passes + (long) (m * 100) + pi);
                    if (cam == null) continue;
                    n++;
                    for (int i = 0; i < all.size(); i++) {
                        int v = all.get(i).run(cam, code);
                        if (v == 1) ok[i]++;
                        if (v == 2) wrong[i]++;
                    }
                }
                if (n == 0) continue;
                frames += n;
                System.out.printf("%-7.2f %-4d", m, passes);
                for (int i = 0; i < all.size(); i++) {
                    System.out.printf(" %-11s", ok[i] + "/" + wrong[i] + "/" + n);
                    sum[i] += ok[i];
                    bad[i] += wrong[i];
                }
                System.out.println();
            }
        }
        System.out.println("------------------------------------------------------------------------------------------------");
        System.out.printf("%-12s", "合计/" + frames);
        for (int i = 0; i < all.size(); i++) {
            System.out.printf(" %-11s", sum[i] + "/" + bad[i] + "/" + frames);
        }
        System.out.println();
        System.out.printf("%-12s", "识别率");
        for (int i = 0; i < all.size(); i++) {
            System.out.printf(" %-11s", String.format("%.0f%%", 100.0 * sum[i] / frames));
        }
        System.out.println();
    }
}
