package com.pankscan.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 用实拍照片检验解码链。
 * 照片里的条码是真实打印质量（含真实墨点、纸张反光、镜头像差），
 * 比合成渲染可信；把它按不同比例缩到「手机取景时能看到的像素量」，
 * 再叠加模糊/噪声/对比度衰减，比较各方案的成功率。
 */
public class PhotoProbe {

    static final String PHOTO =
            "C:/Users/yuguo/.workbuddy/clipboard-images/clipboard-2026-11-02T13-54-35-018Z-1d5fdbd9.jpg";

    /** 手机竖屏预览（1080x1920 转正后） */
    static final int FW = 1080, FH = 1920;
    /** 18 字符 Code128 = 233 模块 */
    static final int MODULES = 233;
    static final String EXPECT = "LXT261102AC0000078";

    static int W, H;
    static byte[] gray;
    static int[] box;      // 条码 bbox: x0,y0,x1,y1

    public static void main(String[] args) throws Exception {
        load();
        System.out.println("照片 " + W + "x" + H);

        box = locate();
        if (box == null) {
            System.out.println("原图未能解出，样本无效");
            return;
        }
        int bw = box[2] - box[0];
        System.out.printf("条码 bbox x %d~%d  y %d~%d  → 宽 %dpx，高 %dpx%n",
                box[0], box[2], box[1], box[3], bw, box[3] - box[1]);
        System.out.printf("模块宽 %.2fpx（%d 模块），条高/模块 = %.1f%n",
                bw / (double) MODULES, MODULES, (box[3] - box[1]) / (bw / (double) MODULES));

        String mode = args.length > 0 ? args[0] : "sim";
        if ("pos".equals(mode)) { runPos(); return; }
        if ("sim".equals(mode)) runSim();
        else if ("sheet".equals(mode)) runSheet();
    }

    /* ================= 载入与定位 ================= */

    static void load() throws Exception {
        BufferedImage im = ImageIO.read(new File(PHOTO));
        W = im.getWidth();
        H = im.getHeight();
        gray = new byte[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = im.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                gray[y * W + x] = (byte) ((r * 77 + g * 151 + b * 28) >> 8);
            }
        }
    }

    /** 用 ZXing 返回的结果点定位条码（一维码的定位点就是首尾两个端点） */
    static int[] locate() {
        for (boolean hard : new boolean[]{false, true}) {
            Result r = decodeResult(gray, W, H, reader(hard, BarcodeFormat.CODE_128), 0);
            if (r == null) continue;
            ResultPoint[] pts = r.getResultPoints();
            if (pts == null || pts.length < 2) continue;
            float x0 = Float.MAX_VALUE, x1 = -1, yc = 0;
            for (ResultPoint p : pts) {
                if (p == null) continue;
                x0 = Math.min(x0, p.getX());
                x1 = Math.max(x1, p.getX());
                yc += p.getY();
            }
            yc /= pts.length;
            int cx0 = (int) x0, cx1 = (int) x1;
            // 条高：在这个 x 区间内，逐行数黑白跳变，跳变密集的行就是条码本体
            int top = -1, bot = -1;
            int mid = (int) yc;
            for (int y = mid; y >= Math.max(0, mid - 200); y--) {
                if (edgesIn(y, cx0, cx1) > 30) top = y;
                else break;
            }
            for (int y = mid; y < Math.min(H, mid + 200); y++) {
                if (edgesIn(y, cx0, cx1) > 30) bot = y;
                else break;
            }
            if (top < 0 || bot < 0) {
                top = mid;
                bot = mid + 1;
            }
            System.out.println("定位点解出：" + r.getText());
            return new int[]{cx0, top, cx1, bot};
        }
        return null;
    }

    static boolean isBarNear(int x, int y) {
        int row = y * W;
        int dark = 0;
        for (int dx = -3; dx <= 3; dx++) {
            int xx = x + dx;
            if (xx < 0 || xx >= W) continue;
            if ((gray[row + xx] & 0xFF) < 110) dark++;
        }
        return dark >= 3;
    }

    /** x 区间内的黑白跳变数 */
    static int edgesIn(int y, int x0, int x1) {
        int row = y * W;
        int lo = Math.max(1, x0), hi = Math.min(W - 1, x1);
        int n = 0;
        for (int x = lo; x < hi; x++) {
            int d = (gray[row + x + 1] & 0xFF) - (gray[row + x] & 0xFF);
            if (d > 45 || d < -45) n++;
        }
        return n;
    }

    /* ================= 缩放模拟 ================= */

    /** 把条码裁片按目标宽度缩放后贴到 1080x1920 画布上 */
    static byte[] compose(int barW, float cyFrac, int blurPasses, float contrast, int noise, long seed) {
        int pad = 18;
        int sx0 = Math.max(0, box[0] - pad), sy0 = Math.max(0, box[1] - pad);
        int sx1 = Math.min(W, box[2] + pad), sy1 = Math.min(H, box[3] + pad);
        int sw = sx1 - sx0, sh = sy1 - sy0;

        double scale = barW / (double) (box[2] - box[0]);
        int dw = (int) Math.round(sw * scale);
        int dh = (int) Math.round(sh * scale);
        if (dw > FW) {
            scale = (FW - 8) / (double) (box[2] - box[0]);
            dw = (int) Math.round(sw * scale);
            dh = (int) Math.round(sh * scale);
        }
        if (dh > FH) return null;
        barW = (int) Math.round((box[2] - box[0]) * scale);

        byte[] canvas = new byte[FW * FH];
        java.util.Arrays.fill(canvas, (byte) 176);

        byte[] chip = resample(sx0, sy0, sw, sh, dw, dh);
        int ox = (FW - dw) / 2;
        int oy = (int) (cyFrac * FH) - dh / 2;
        for (int y = 0; y < dh; y++) {
            int ty = oy + y;
            if (ty < 0 || ty >= FH) continue;
            int srow = y * dw, drow = ty * FW + ox;
            for (int x = 0; x < dw; x++) {
                int tx = ox + x;
                if (tx < 0 || tx >= FW) continue;
                int v = chip[srow + x] & 0xFF;
                // 对比度衰减：往中灰压
                v = (int) (128 + (v - 128) * contrast);
                canvas[drow + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        for (int i = 0; i < blurPasses; i++) blur(canvas, FW, FH);
        if (noise > 0) {
            Random rnd = new Random(seed);
            for (int i = 0; i < canvas.length; i++) {
                int v = (canvas[i] & 0xFF) + rnd.nextInt(noise * 2 + 1) - noise;
                canvas[i] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        lastBarW = barW;
        return canvas;
    }

    static int lastBarW;

    static byte[] resample(int sx, int sy, int sw, int sh, int dw, int dh) {
        byte[] out = new byte[dw * dh];
        double rx = sw / (double) dw, ry = sh / (double) dh;
        for (int y = 0; y < dh; y++) {
            double fy = sy + (y + 0.5) * ry - 0.5;
            int y0 = (int) Math.floor(fy);
            double wy = fy - y0;
            int y1 = Math.min(sy + sh - 1, Math.max(sy, y0 + 1));
            y0 = Math.min(sy + sh - 1, Math.max(sy, y0));
            for (int x = 0; x < dw; x++) {
                double fx = sx + (x + 0.5) * rx - 0.5;
                int x0 = (int) Math.floor(fx);
                double wx = fx - x0;
                int x1 = Math.min(sx + sw - 1, Math.max(sx, x0 + 1));
                x0 = Math.min(sx + sw - 1, Math.max(sx, x0));
                double v00 = gray[y0 * W + x0] & 0xFF, v01 = gray[y0 * W + x1] & 0xFF;
                double v10 = gray[y1 * W + x0] & 0xFF, v11 = gray[y1 * W + x1] & 0xFF;
                double top = v00 + (v01 - v00) * wx;
                double bot = v10 + (v11 - v10) * wx;
                out[y * dw + x] = (byte) (int) Math.round(top + (bot - top) * wy);
            }
        }
        return out;
    }

    /** 3x3 高斯近似（1-2-1） */
    static void blur(byte[] b, int w, int h) {
        byte[] t = new byte[b.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int l = x > 0 ? (b[row + x - 1] & 0xFF) : (b[row + x] & 0xFF);
                int c = b[row + x] & 0xFF;
                int r = x < w - 1 ? (b[row + x + 1] & 0xFF) : c;
                t[row + x] = (byte) ((l + 2 * c + r) >> 2);
            }
        }
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int u = y > 0 ? (t[row - w + x] & 0xFF) : (t[row + x] & 0xFF);
                int c = t[row + x] & 0xFF;
                int d = y < h - 1 ? (t[row + w + x] & 0xFF) : c;
                b[row + x] = (byte) ((u + 2 * c + d) >> 2);
            }
        }
    }

    /* ================= 解码 ================= */

    static MultiFormatReader reader(boolean hard, BarcodeFormat only) {
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> f = new ArrayList<>();
        if (only != null) {
            f.add(only);
        } else {
            f.add(BarcodeFormat.CODE_128);
            f.add(BarcodeFormat.CODE_39);
            f.add(BarcodeFormat.CODE_93);
            f.add(BarcodeFormat.EAN_13);
            f.add(BarcodeFormat.EAN_8);
            f.add(BarcodeFormat.ITF);
            f.add(BarcodeFormat.UPC_A);
            f.add(BarcodeFormat.UPC_E);
        }
        h.put(DecodeHintType.POSSIBLE_FORMATS, f);
        h.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        h.put(DecodeHintType.TRY_HARDER, hard ? Boolean.TRUE : Boolean.FALSE);
        MultiFormatReader r = new MultiFormatReader();
        r.setHints(h);
        return r;
    }

    static Result decodeResult(byte[] buf, int w, int h, MultiFormatReader r, int bin) {
        if (buf == null || w < 8 || h < 8) return null;
        try {
            PlanarYUVLuminanceSource src =
                    new PlanarYUVLuminanceSource(buf, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(bin == 0
                    ? new HybridBinarizer(src) : new GlobalHistogramBinarizer(src));
            return r.decodeWithState(bmp);
        } catch (ReaderException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String decode(byte[] buf, int w, int h, MultiFormatReader r, int bin) {
        Result res = decodeResult(buf, w, h, r, bin);
        return res == null ? null : res.getText();
    }

    static void runSim() {
        System.out.println();
        System.out.println("=== 实拍照片降质模拟：1080x1920 画布，条码按不同宽度贴入 ===");
        System.out.println("现状 = v1.5 解码链；改进 = 全宽带 + 自动定位 + 128优先 + 格式校验");
        System.out.println();

        int[] barWs = {180, 220, 260, 300, 340, 400, 480};
        int[] blurs = {0, 1, 2, 3};
        float[] contrasts = {0.85f, 0.62f};

        System.out.printf("%-6s %-5s %-5s", "条宽", "模块", "模糊");
        for (int i = 0; i < 4; i++) System.out.printf(" %-12s", TAG[i]);
        System.out.println("    （对/错/总）");
        System.out.println("--------------------------------------------------------------");

        int[] sum = new int[4];
        int[] bad = new int[4];
        int total = 0;
        for (int bw : barWs) {
            for (int bl : blurs) {
                for (float ct : contrasts) {
                    int[] ok = new int[4];
                    int[] wg = new int[4];
                    int n = 0;
                    for (int ph = 0; ph < 6; ph++) {
                        // 亚像素相位：整体左右平移 1/6 像素
                        byte[] canvas = composePhased(bw, 0.46f, bl, ct, 3, 9001L + bw + bl * 17 + ph, ph);
                        if (canvas == null) continue;
                        n++;
                        int[] v = evaluate(canvas);
                        for (int i = 0; i < 4; i++) {
                            if (v[i] == 1) ok[i]++;
                            if (v[i] == 2) wg[i]++;
                        }
                    }
                    if (n == 0) continue;
                    total += n;
                    System.out.printf("%-6d %-5.2f %-5d", bw, bw / (double) MODULES, bl);
                    for (int i = 0; i < 4; i++) {
                        System.out.printf(" %-12s", ok[i] + "/" + wg[i] + "/" + n);
                        sum[i] += ok[i];
                        bad[i] += wg[i];
                    }
                    System.out.println("  对比度 " + ct);
                }
            }
        }
        System.out.println("--------------------------------------------------------------");
        System.out.printf("%-18s", "合计（对/错）");
        for (int i = 0; i < 4; i++) System.out.printf(" %-12s", sum[i] + "/" + bad[i]);
        System.out.println();
        System.out.printf("%-18s", "识别率");
        for (int i = 0; i < 4; i++) System.out.printf(" %-12s", pct(sum[i], total));
        System.out.println();

        runPos();
    }

    static String pct(int a, int b) {
        return b == 0 ? "-" : String.format("%.0f%%", 100.0 * a / b);
    }

    static final String[] TAG = {"现状", "+全宽带", "+定位", "+128优先"};

    /** 位置敏感度：条码在画面里的纵向位置变化 */
    static void runPos() {
        System.out.println();
        System.out.println("=== 纵向位置敏感度（条宽 300，模块 1.29px，模糊 1，对比度 0.72）===");
        float[] cys = {0.18f, 0.30f, 0.46f, 0.62f, 0.76f, 0.88f};
        System.out.printf("%-8s", "条码中心");
        for (int i = 0; i < 4; i++) System.out.printf(" %-10s", TAG[i]);
        System.out.println();
        int[] sum = new int[4];
        int total = 0;
        for (float cy : cys) {
            int[] ok = new int[4];
            int n = 0;
            for (int ph = 0; ph < 6; ph++) {
                byte[] canvas = composePhased(300, cy, 1, 0.72f, 3, 4400L + ph + (long) (cy * 100), ph);
                if (canvas == null) continue;
                n++;
                int[] v = evaluate(canvas);
                for (int i = 0; i < 4; i++) if (v[i] == 1) ok[i]++;
            }
            total += n;
            System.out.printf("%-8.2f", cy);
            for (int i = 0; i < 4; i++) {
                System.out.printf(" %-10s", pct(ok[i], n));
                sum[i] += ok[i];
            }
            System.out.println();
        }
        System.out.printf("%-8s", "合计");
        for (int i = 0; i < 4; i++) System.out.printf(" %-10s", pct(sum[i], total));
        System.out.println();
    }

    /** 带亚像素平移的贴图：ph/6 像素的整体水平位移 */
    static byte[] composePhased(int barW, float cyFrac, int blurPasses, float contrast,
                                int noise, long seed, int ph) {
        float dx = ph / 6f;
        int pad = 18;
        int sx0 = Math.max(0, box[0] - pad), sy0 = Math.max(0, box[1] - pad);
        int sx1 = Math.min(W, box[2] + pad), sy1 = Math.min(H, box[3] + pad);
        int sw = sx1 - sx0, sh = sy1 - sy0;

        double scale = barW / (double) (box[2] - box[0]);
        int dw = (int) Math.round(sw * scale);
        int dh = (int) Math.round(sh * scale);
        if (dw > FW - 8) return null;
        if (dh > FH) return null;

        byte[] canvas = new byte[FW * FH];
        java.util.Arrays.fill(canvas, (byte) 176);

        byte[] chip = resample(sx0, sy0, sw, sh, dw, dh, dx);
        int ox = (FW - dw) / 2 + (int) Math.floor(dx);
        int oy = (int) (cyFrac * FH) - dh / 2;
        for (int y = 0; y < dh; y++) {
            int ty = oy + y;
            if (ty < 0 || ty >= FH) continue;
            int srow = y * dw, drow = ty * FW + ox;
            for (int x = 0; x < dw; x++) {
                int tx = ox + x;
                if (tx < 0 || tx >= FW) continue;
                int v = chip[srow + x] & 0xFF;
                v = (int) (128 + (v - 128) * contrast);
                canvas[drow + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        for (int i = 0; i < blurPasses; i++) blur(canvas, FW, FH);
        if (noise > 0) {
            Random rnd = new Random(seed);
            for (int i = 0; i < canvas.length; i++) {
                int v = (canvas[i] & 0xFF) + rnd.nextInt(noise * 2 + 1) - noise;
                canvas[i] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        return canvas;
    }

    static byte[] resample(int sx, int sy, int sw, int sh, int dw, int dh, float dx) {
        byte[] out = new byte[dw * dh];
        double rx = sw / (double) dw, ry = sh / (double) dh;
        for (int y = 0; y < dh; y++) {
            double fy = sy + (y + 0.5) * ry - 0.5;
            int y0 = (int) Math.floor(fy);
            double wy = fy - y0;
            int y1 = Math.min(sy + sh - 1, Math.max(sy, y0 + 1));
            y0 = Math.min(sy + sh - 1, Math.max(sy, y0));
            for (int x = 0; x < dw; x++) {
                double fx = sx + (x + 0.5 + dx) * rx - 0.5;
                int x0 = (int) Math.floor(fx);
                double wx = fx - x0;
                int x1 = Math.min(sx + sw - 1, Math.max(sx, x0 + 1));
                x0 = Math.min(sx + sw - 1, Math.max(sx, x0));
                double v00 = gray[y0 * W + x0] & 0xFF, v01 = gray[y0 * W + x1] & 0xFF;
                double v10 = gray[y1 * W + x0] & 0xFF, v11 = gray[y1 * W + x1] & 0xFF;
                double top = v00 + (v01 - v00) * wx;
                double bot = v10 + (v11 - v10) * wx;
                out[y * dw + x] = (byte) (int) Math.round(top + (bot - top) * wy);
            }
        }
        return out;
    }

    /* ================= 四条解码链 ================= */

    /** 返回 [现状, +全宽带, +定位, +128优先] 各自的 0=失败 1=对 2=错 */
    static int[] evaluate(byte[] cam) {
        String[] r = {
                chainNowText(cam), chainWideText(cam),
                chainLocText(cam), chain128Text(cam)
        };
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) v[i] = verdict(r[i]);
        return v;
    }

    static int verdict(String got) {
        if (got == null || got.isEmpty()) return 0;
        return got.equals(EXPECT) ? 1 : 2;
    }

    /** 现状：v1.5 逐字复刻（全幅128快路 → 固定带三档偏置之一 → 全幅全格式 → 全幅TRY_HARDER） */
    static String chainNowText(byte[] cam) {
        String s = decode(cam, FW, FH, reader(false, BarcodeFormat.CODE_128), 0);
        if (s != null) return s;

        ImageOps ops = new ImageOps();
        byte[] band = ops.cropUpscale(cam, FW, FH, 0.15f, 0.30f, 0.85f, 0.62f, 1);
        if (band != null) {
            int bw = ops.outWidth(), bh = ops.outHeight();
            if (bw >= 40 && bh >= 20) {
                ops.sharpen(band, bw, bh);
                // 真机是三档偏置逐帧轮换，三帧内都会试到；这里一次性全试，才算公平
                for (float bias : new float[]{0f, -0.12f, 0.12f}) {
                    int shift = (int) (bias * 255f);
                    if (shift != 0) ops.shift(band, bw * bh, shift);
                    s = decode(band, bw, bh, reader(true, null), 0);
                    if (s == null) s = decode(band, bw, bh, reader(true, null), 1);
                    if (shift != 0) ops.shift(band, bw * bh, -shift);
                    if (s != null) return s;
                }
            }
        }
        s = decode(cam, FW, FH, reader(false, null), 0);
        if (s != null) return s;
        return decode(cam, FW, FH, reader(true, null), 0);
    }

    /** +全宽带：水平范围从 0.15~0.85 放到 0.02~0.98 */
    static String chainWideText(byte[] cam) {
        String s = decode(cam, FW, FH, reader(false, BarcodeFormat.CODE_128), 0);
        if (s != null) return s;
        s = bandTryText(cam, 0.30f, 0.62f, 0.02f, 0.98f, null);
        if (s != null) return s;
        s = decode(cam, FW, FH, reader(false, null), 0);
        if (s != null) return s;
        return decode(cam, FW, FH, reader(true, null), 0);
    }

    /** +定位：纵向范围由行梯度自动求出 */
    static String chainLocText(byte[] cam) {
        String s = decode(cam, FW, FH, reader(false, BarcodeFormat.CODE_128), 0);
        if (s != null) return s;
        int[] band = locateRows(cam);
        if (band != null) {
            int pad = (int) ((band[1] - band[0]) * 0.12f);
            int t = Math.max(0, band[0] - pad), b = Math.min(FH, band[1] + pad);
            s = bandTryText(cam, t / (float) FH, b / (float) FH, 0.02f, 0.98f, null);
            if (s != null) return s;
        }
        s = decode(cam, FW, FH, reader(false, null), 0);
        if (s != null) return s;
        return decode(cam, FW, FH, reader(true, null), 0);
    }

    /** +128优先 + 格式校验 */
    static String chain128Text(byte[] cam) {
        String s = decode(cam, FW, FH, reader(false, BarcodeFormat.CODE_128), 0);
        if (s != null) return s;
        int[] band = locateRows(cam);
        if (band != null) {
            int pad = (int) ((band[1] - band[0]) * 0.12f);
            int t = Math.max(0, band[0] - pad), b = Math.min(FH, band[1] + pad);
            s = bandTryText(cam, t / (float) FH, b / (float) FH, 0.02f, 0.98f, Boolean.TRUE);
            if (s != null) return s;
        }
        s = decode(cam, FW, FH, reader(false, null), 0);
        if (s != null && fmtOk(s)) return s;
        s = decode(cam, FW, FH, reader(true, null), 0);
        return (s != null && fmtOk(s)) ? s : null;
    }

    /** 裁剪 + 锐化 + 三档偏置；fmtOnly 为真时只要格式合规的结果 */
    static String bandTryText(byte[] cam, float t, float b, float l, float r, Boolean fmtOnly) {
        ImageOps ops = new ImageOps();
        byte[] buf = ops.cropUpscale(cam, FW, FH, l, t, r, b, 1);
        if (buf == null) return null;
        int bw = ops.outWidth(), bh = ops.outHeight();
        if (bw < 40 || bh < 20) return null;
        ops.sharpen(buf, bw, bh);
        MultiFormatReader only128 = fmtOnly == null ? null : reader(true, BarcodeFormat.CODE_128);

        for (int pass = 0; pass < 2; pass++) {
            for (float bias : new float[]{-0.12f, 0f, 0.12f}) {
                int shift = (int) (bias * 255f);
                if (shift != 0) ops.shift(buf, bw * bh, shift);
                for (int bin = 0; bin < 2; bin++) {
                    String g = only128 != null && pass == 0
                            ? decode(buf, bw, bh, only128, bin)
                            : decode(buf, bw, bh, reader(true, null), bin);
                    if (g != null && (fmtOnly == null || fmtOk(g))) {
                        if (shift != 0) ops.shift(buf, bw * bh, -shift);
                        return g;
                    }
                }
                if (shift != 0) ops.shift(buf, bw * bh, -shift);
            }
        }
        return null;
    }

    /** 格式校验：18 位、全大写字母和数字 */
    static boolean fmtOk(String s) {
        if (s == null || s.length() != EXPECT.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** 在给定纵向比例范围内裁剪、锐化、试三档偏置 */
    static String bandTry(byte[] cam, float t, float b, float l, float r, boolean fmtOnly) {
        ImageOps ops = new ImageOps();
        byte[] buf = ops.cropUpscale(cam, FW, FH, l, t, r, b, 1);
        if (buf == null) return null;
        int bw = ops.outWidth(), bh = ops.outHeight();
        if (bw < 40 || bh < 20) return null;
        ops.sharpen(buf, bw, bh);
        for (float bias : new float[]{-0.12f, 0f, 0.12f}) {
            int shift = (int) (bias * 255f);
            if (shift != 0) ops.shift(buf, bw * bh, shift);
            String g = decode(buf, bw, bh, reader(true, null), 0);
            if (g != null && (!fmtOnly || fmtOk(g))) return g;
            g = decode(buf, bw, bh, reader(true, null), 1);
            if (g != null && (!fmtOnly || fmtOk(g))) return g;
            if (shift != 0) ops.shift(buf, bw * bh, -shift);
        }
        return null;
    }

    /** 行梯度定位条码上下边界 */
    static int[] locateRows(byte[] frame) {
        int n = FH;
        int[] cnt = new int[n];
        for (int y = 0; y < n; y++) {
            int row = y * FW;
            int c = 0;
            for (int x = 1; x < FW - 1; x++) {
                int d = (frame[row + x + 1] & 0xFF) - (frame[row + x - 1] & 0xFF);
                if (d > 45 || d < -45) c++;
            }
            cnt[y] = c;
        }
        int bestS = -1, bestE = -1, bestLen = 0;
        int s = -1;
        for (int y = 0; y < n; y++) {
            if (cnt[y] > 30) {
                if (s < 0) s = y;
            } else if (s >= 0) {
                if (y - s > bestLen) {
                    bestLen = y - s;
                    bestS = s;
                    bestE = y;
                }
                s = -1;
            }
        }
        if (s >= 0 && n - s > bestLen) {
            bestS = s;
            bestE = n;
        }
        return bestS < 0 ? null : new int[]{bestS, bestE};
    }

    /** 整张标签纸进画面（照片原样缩放），5 个条码任读出一个即算成功 */
    static void runSheet() {
        String[] codes = {"LXT261102AC0000069", "LXT261102AC0000073", "LXT261102AC0000077",
                "LXT261102AC0000078", "LXT261102AC0000074"};
        int[] widths = {1080, 990, 920, 860, 800, 740, 680};
        int[] blurs = {0, 1, 2};

        System.out.println();
        System.out.println("=== 整张标签纸进画面（照片按比例缩放到画布宽度）===");
        System.out.printf("%-8s %-6s %-6s", "照片宽", "条宽", "模糊");
        for (int i = 0; i < 4; i++) System.out.printf(" %-12s", TAG[i]);
        System.out.println();

        int[] ok = new int[4], bad = new int[4], total = new int[4];
        for (int tw : widths) {
            double k = tw / (double) W;
            int th = (int) Math.round(H * k);
            for (int bl : blurs) {
                int[] o = new int[4], wg = new int[4];
                for (int ph = 0; ph < 6; ph++) {
                    byte[] canvas = sheet(tw, th, k, bl, ph);
                    if (canvas == null) continue;
                    int[] v = evaluateMulti(canvas, codes);
                    for (int i = 0; i < 4; i++) {
                        if (v[i] == 1) o[i]++;
                        if (v[i] == 2) wg[i]++;
                        total[i]++;
                    }
                }
                System.out.printf("%-8d %-6d %-6d", tw, (int) (444 * k), bl);
                for (int i = 0; i < 4; i++) {
                    System.out.printf(" %-12s", o[i] + "/" + wg[i] + "/6");
                    ok[i] += o[i];
                    bad[i] += wg[i];
                }
                System.out.println();
            }
        }
        System.out.printf("%-21s", "合计（对/错）");
        for (int i = 0; i < 4; i++) System.out.printf(" %-12s", ok[i] + "/" + bad[i]);
        System.out.println();
        System.out.printf("%-21s", "识别率");
        for (int i = 0; i < 4; i++) System.out.printf(" %-12s", pct(ok[i], total[i]));
        System.out.println();
    }

    static byte[] sheet(int tw, int th, double k, int blurPasses, int ph) {
        if (tw > FW || th > FH) return null;
        byte[] chip = resample(0, 0, W, H, tw, th, ph / 6f);
        byte[] canvas = new byte[FW * FH];
        java.util.Arrays.fill(canvas, (byte) 176);
        int ox = (FW - tw) / 2, oy = (FH - th) / 2;
        for (int y = 0; y < th; y++) {
            int drow = (oy + y) * FW + ox;
            System.arraycopy(chip, y * tw, canvas, drow, tw);
        }
        for (int i = 0; i < blurPasses; i++) blur(canvas, FW, FH);
        return canvas;
    }

    static int[] evaluateMulti(byte[] cam, String[] codes) {
        String[] r = {chainNowText(cam), chainWideText(cam),
                chainLocText(cam), chain128Text(cam)};
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            String s = r[i];
            v[i] = 0;
            if (s != null) {
                for (String c : codes) if (c.equals(s)) {
                    v[i] = 1;
                    break;
                }
                if (v[i] == 0) v[i] = 2;
            }
        }
        return v;
    }
}
