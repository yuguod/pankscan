package com.pankscan.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 用标签纸实际尺寸（40mm × 15mm）标定这张照片，回答三个问题：
 *
 *   1. 一个模块在实物上有多宽（mm）？
 *   2. 这个宽度落到 203dpi / 300dpi 打印机的点阵上是几个点？
 *   3. 40mm 宽的标签上，18 字符 Code 128 到底能不能印到 2 点/模块？
 *
 * 只做测量，不改算法。
 */
public class LabelCalib {

    static final double LABEL_MM_W = 40.0;
    static final double LABEL_MM_H = 15.0;
    static final int COUNT = 5;

    /** 18 字符 Code 128：起始 + 18 数据 + 校验 = 20 个符号字符，加终止符
     *  20×11 + 13 = 233 模块；两侧静区各 10 模块 */
    static final int MODULES = 233;
    static final int QUIET = 10;

    static int W, H;
    static byte[] gray, lum, sat;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
                : "C:/Users/yuguo/.workbuddy/clipboard-images/clipboard-2026-11-02T13-54-35-018Z-1d5fdbd9.jpg";
        BufferedImage im = ImageIO.read(new File(path));
        W = im.getWidth();
        H = im.getHeight();
        System.out.printf("照片 %d x %d（%.1f MP）%n", W, H, W * H / 1e6);

        gray = new byte[W * H];
        lum = new byte[W * H];
        sat = new byte[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = im.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                int i = y * W + x;
                lum[i] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
                sat[i] = (byte) Math.min(255, mx - mn);
                gray[i] = (byte) ((r + g * 2 + b) / 4);
            }
        }

        // 1) 白色标签区的横向范围：5 张同宽纵向排列，整块宽度就是 40mm
        int[] xr = whiteXRange();
        if (xr == null) {
            System.out.println("没找到白色标签区");
            return;
        }
        int labelPxW = xr[1] - xr[0] + 1;
        double pxPerMm = labelPxW / LABEL_MM_W;
        System.out.printf("%n标签白区横向 %d~%d，宽 %d px%n", xr[0], xr[1], labelPxW);
        System.out.printf("标定：%.3f px/mm%n", pxPerMm);

        // 2) 逐个条码量宽度
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            Bar b = measureOne(i, xr);
            if (b != null) bars.add(b);
        }
        if (bars.isEmpty()) {
            System.out.println("没有量到条码");
            return;
        }

        System.out.printf("%n=== 逐条测量（%d 个条码）===%n", bars.size());
        System.out.printf("%-20s %-13s %-9s %-9s %-9s%n",
                "内容", "条宽x条高px", "条宽mm", "模块px", "模块mm");
        double sumModMm = 0;
        double sumModPx = 0;
        for (Bar b : bars) {
            int bw = b.x1 - b.x0 + 1;
            int bh = b.y1 - b.y0 + 1;
            double modPx = bw / (double) MODULES;
            double modMm = bw / pxPerMm / MODULES;
            System.out.printf("%-20s %-13s %-9.2f %-9.2f %-9.3f%n",
                    b.text == null ? "(未解出)" : b.text,
                    bw + "x" + bh, bw / pxPerMm, modPx, modMm);
            sumModMm += modMm;
            sumModPx += modPx;
        }
        double modMm = sumModMm / bars.size();
        double modPx = sumModPx / bars.size();
        double barMm = bars.get(0).x1 - bars.get(0).x0 + 1;
        barMm /= pxPerMm;

        System.out.printf("%n平均：条码宽 %.2f mm，模块 %.2f px = %.4f mm%n", barMm, modPx, modMm);
        System.out.printf("条高/模块 = %.0f（Code 128 要求 >= 15%% 或 >= 6mm，这个比例正常）%n",
                ((bars.get(0).y1 - bars.get(0).y0 + 1) / modPx));

        // 3) 换算到打印机点阵
        System.out.println("\n=== 现在的模块宽度落到打印机点阵上是几个点 ===");
        int[] dpi = {203, 300, 600};
        for (int d : dpi) {
            double dpmm = d / 25.4;
            double dots = modMm * dpmm;
            System.out.printf("  %3ddpi（%.2f 点/mm）：%.2f 点/模块   条码 %.1f mm   含静区 %.1f mm%s%n",
                    d, dpmm, dots,
                    MODULES * modMm, (MODULES + QUIET * 2) * modMm,
                    dots < 1.5 ? "   ← 严重失真" : (dots < 2.0 ? "   ← 临界" : ""));
        }

        // 4) 反向：40mm 标签最多能做到多少
        System.out.println("\n=== 40mm 标签上，18 字符 Code 128 每模块能做到几个点 ===");
        double avail = LABEL_MM_W * 0.94;
        for (int d : dpi) {
            double dpmm = d / 25.4;
            double dots = avail * dpmm;
            double per = dots / (MODULES + QUIET * 2);
            System.out.printf("  %3ddpi：可用 %.0f 点 / %d 模块 = %.2f 点/模块%s%n",
                    d, dots, MODULES + QUIET * 2, per,
                    per >= 2.0 ? "   ← 可以" : "   ← 不到 2 点，模块宽度必然在 1/2 点之间跳");
        }

        System.out.println("\n=== 反过来：要 2 点/模块，40mm 标签最多放多少模块 ===");
        for (int d : dpi) {
            double dpmm = d / 25.4;
            double maxMod = avail * dpmm / 2.0;
            // 模块数 = 11n + 13（n 个符号字符）
            int nSym = (int) ((maxMod - 13) / 11);
            int payload = nSym - 3;    // 去起始符、校验位、终止符占位
            System.out.printf("  %3ddpi：%.0f 模块 → %d 个符号字符 → 纯数据约 %d 字符%s%n",
                    d, maxMod, nSym, Math.max(0, payload),
                    Math.max(0, payload) >= 18 ? "   ← 18 字符放得下" : "   ← 放不下 18 字符");
        }
    }

    /* ---------------- 定位 ---------------- */

    /** 白色像素的横向外接范围；白色 = 亮且低饱和（木桌棕、离型纸灰都排除） */
    static int[] whiteXRange() {
        int lo = W, hi = -1;
        // 用行投影先排除边缘，只在中段取，避免木纹高光
        for (int y = (int) (H * 0.05); y < H * 0.95; y++) {
            int first = -1, last = -1, run = 0;
            int cfgFirst = -1, cfgLast = -1, bestRun = 0;
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                boolean w = (lum[i] & 0xFF) > 155 && (sat[i] & 0xFF) < 22;
                if (w) {
                    if (first < 0) first = x;
                    last = x;
                    run++;
                }
            }
            // 只认跨度足够大的行（整块标签约 40mm，占画面 1/4 以上）
            if (first >= 0 && run > W * 0.18) {
                if (first < lo) lo = first;
                if (last > hi) hi = last;
            }
        }
        return hi < 0 ? null : new int[]{lo, hi};
    }

    static class Bar {
        int x0, y0, x1, y1;
        String text;
    }

    /** 逐张标签：先按纵向切段（白像素多的连续行），再解出条码拿定位点 */
    static Bar measureOne(int idx, int[] xr) {
        int[] yr = labelBand(idx);
        if (yr == null) return null;

        int bw = xr[1] - xr[0] + 1;
        int bh = yr[1] - yr[0] + 1;
        if (bw < 50 || bh < 30) return null;
        byte[] sub = new byte[bw * bh];
        for (int y = 0; y < bh; y++) {
            System.arraycopy(gray, (yr[0] + y) * W + xr[0], sub, y * bw, bw);
        }

        Result r = decode(sub, bw, bh, false);
        if (r == null) r = decode(sub, bw, bh, true);
        if (r == null) return null;

        ResultPoint[] pts = r.getResultPoints();
        if (pts == null || pts.length < 2) return null;

        Bar b = new Bar();
        b.text = r.getText();
        b.x0 = xr[0] + Math.round(Math.min(pts[0].getX(), pts[1].getX()));
        b.x1 = xr[0] + Math.round(Math.max(pts[0].getX(), pts[1].getX()));

        // 纵向：两个定位点的中间列，向上/下找连续黑条
        int cx = (b.x0 + b.x1) / 2;
        int cy = yr[0] + Math.round((pts[0].getY() + pts[1].getY()) / 2);
        int top = cy, bot = cy;
        while (top > yr[0] && barRow(top - 1, b.x0, b.x1)) top--;
        while (bot < yr[1] && barRow(bot + 1, b.x0, b.x1)) bot++;
        b.y0 = top;
        b.y1 = bot;
        return b;
    }

    /** 第 idx 张标签的纵向范围：把标签区按白像素行分布切成 COUNT 段 */
    static int[] labelBand(int idx) {
        int[] cnt = new int[H];
        for (int y = 0; y < H; y++) {
            int c = 0;
            for (int x = 0; x < W; x += 2) {
                int i = y * W + x;
                if ((lum[i] & 0xFF) > 155 && (sat[i] & 0xFF) < 22) c++;
            }
            cnt[y] = c;
        }
        int thr = (int) (W * 0.18 / 2);
        List<int[]> segs = new ArrayList<>();
        int start = -1, gap = 0;
        for (int y = 0; y < H; y++) {
            if (cnt[y] > thr) {
                if (start < 0) start = y;
                gap = 0;
            } else if (start >= 0) {
                if (++gap > 4) {
                    if (y - gap - start > H * 0.05) segs.add(new int[]{start, y - gap});
                    start = -1;
                    gap = 0;
                }
            }
        }
        if (start >= 0) segs.add(new int[]{start, H - 1});
        if (segs.size() < COUNT) {
            // 缝隙太细导致粘连：按整块高度均分
            if (segs.size() == 1) {
                int y0 = segs.get(0)[0], y1 = segs.get(0)[1];
                int step = (y1 - y0) / COUNT;
                return new int[]{y0 + idx * step, y0 + (idx + 1) * step};
            }
            return null;
        }
        return segs.get(Math.min(idx, segs.size() - 1));
    }

    static boolean barRow(int y, int x0, int x1) {
        int dark = 0, n = 0;
        for (int x = x0; x <= x1; x += 2) {
            if ((gray[y * W + x] & 0xFF) < 110) dark++;
            n++;
        }
        return n > 0 && dark > n * 0.15;
    }

    static Result decode(byte[] buf, int w, int h, boolean hard) {
        try {
            PlanarYUVLuminanceSource src =
                    new PlanarYUVLuminanceSource(buf, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
            List<BarcodeFormat> f = new ArrayList<BarcodeFormat>();
            f.add(BarcodeFormat.CODE_128);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, f);
            hints.put(DecodeHintType.TRY_HARDER, hard ? Boolean.TRUE : Boolean.FALSE);
            MultiFormatReader rd = new MultiFormatReader();
            rd.setHints(hints);
            return rd.decodeWithState(bmp);
        } catch (Throwable t) {
            return null;
        }
    }
}
