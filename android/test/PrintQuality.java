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
 * 打印质量体检：不看能不能解，而是量「印出来的模块宽度是不是均匀」。
 *
 * Code 128 的每个游程只允许是 1/2/3/4 个模块宽。把测到的游程按模块宽度归一化，
 * 如果都聚在 1.00/2.00/3.00/4.00 附近，说明打印干净；
 * 如果散成一片，说明转位图 + 缩放把宽度关系搞坏了，这是算法救不回来的。
 *
 * 同时量「边缘过渡宽度」：从黑到白用了几个像素。过渡宽度接近或超过模块宽度，
 * 相邻模块就糊在一起，任何二值化都会丢信息。
 */
public class PrintQuality {

    static int W, H;
    static byte[] gray;
    static double pxPerMm;
    static int modPx;   // 标定得到的模块像素宽（四舍五入留一位小数用 double）

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
                : "C:/Users/yuguo/.workbuddy/clipboard-images/clipboard-2026-11-02T13-54-35-018Z-1d5fdbd9.jpg";
        BufferedImage im = ImageIO.read(new File(path));
        W = im.getWidth();
        H = im.getHeight();
        gray = new byte[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = im.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                gray[y * W + x] = (byte) ((r + g * 2 + b) / 4);
            }
        }

        // 用之前的标定结果：40mm 标签 = 723px
        pxPerMm = 723 / 40.0;
        System.out.printf("标定 %.3f px/mm%n", pxPerMm);

        List<int[]> bands = findBands();
        System.out.println("找到 " + bands.size() + " 张标签");

        int totalRuns = 0;
        double[] devSum = new double[5];   // 按 1/2/3/4 分类的偏差累计
        int[] devCnt = new int[5];
        double edgeSum = 0;
        int edgeCnt = 0;

        for (int i = 0; i < bands.size(); i++) {
            int[] band = bands.get(i);
            Result r = decodeBand(band);
            if (r == null) {
                System.out.printf("  标签%d：解不出，跳过%n", i + 1);
                continue;
            }
            ResultPoint[] pts = r.getResultPoints();
            if (pts == null || pts.length < 2) continue;
            int x0 = (int) Math.min(pts[0].getX(), pts[1].getX());
            int x1 = (int) Math.max(pts[0].getX(), pts[1].getX());
            int barW = x1 - x0 + 1;
            double mod = barW / 233.0;
            int cy = (band[0] + band[1]) / 2;

            // 取条码中段 5 行平均，压掉单行噪声
            double[] prof = new double[barW];
            int rows = 0;
            for (int dy = -2; dy <= 2; dy++) {
                int y = cy + dy;
                if (y < band[0] || y > band[1]) continue;
                for (int k = 0; k < barW; k++) {
                    prof[k] += gray[y * W + x0 + k] & 0xFF;
                }
                rows++;
            }
            for (int k = 0; k < barW; k++) prof[k] /= rows;

            int lo = 255, hi = 0;
            for (double v : prof) {
                if (v < lo) lo = (int) v;
                if (v > hi) hi = (int) v;
            }
            int thr = (lo + hi) / 2;

            // 游程
            List<Integer> runs = new ArrayList<>();
            boolean cur = prof[0] < thr;
            int len = 1;
            for (int k = 1; k < barW; k++) {
                boolean b = prof[k] < thr;
                if (b == cur) len++;
                else {
                    runs.add(len);
                    cur = b;
                    len = 1;
                }
            }
            runs.add(len);

            // 归一化偏差：每个游程应是最接近的整数模块
            double[] hist = new double[5];
            int[] histN = new int[5];
            for (int run : runs) {
                double m = run / mod;
                int nearest = (int) Math.round(m);
                if (nearest < 1 || nearest > 4) {
                    // 落在 1~4 之外的游程本身就是失真证据
                    devSum[0] += Math.abs(m - Math.max(1, Math.min(4, nearest)));
                    devCnt[0]++;
                    continue;
                }
                hist[nearest] += m;
                histN[nearest]++;
                devSum[nearest] += Math.abs(m - nearest);
                devCnt[nearest]++;
            }
            totalRuns += runs.size();

            System.out.printf("%n标签%d  %s%n", i + 1, r.getText());
            System.out.printf("  条宽 %d px，模块 %.2f px，灰度 %d~%d，游程 %d 个%n",
                    barW, mod, lo, hi, runs.size());
            System.out.printf("  游程分布（应为 1/2/3/4 模块）：");
            for (int k = 1; k <= 4; k++) {
                if (histN[k] > 0) {
                    System.out.printf("  %d模块×%d个(实测均值%5.2f) ", k, histN[k], hist[k] / histN[k]);
                }
            }
            System.out.println();

            // 边缘过渡宽度：统计所有跃变处 10%~90% 用了几个像素
            double edge = 0;
            int ec = 0;
            for (int k = 1; k < barW - 1; k++) {
                double d = Math.abs(prof[k] - prof[k - 1]);
                if (d > (hi - lo) * 0.18) {
                    // 找这一段从 10% 到 90% 的宽度
                    boolean up = prof[k] > prof[k - 1];
                    double loT = lo + (hi - lo) * 0.15, hiT = lo + (hi - lo) * 0.85;
                    int a = k - 1, b = k;
                    for (int s = k - 1; s >= Math.max(0, k - 6); s--) {
                        if (up ? prof[s] < loT : prof[s] > hiT) { a = s; break; }
                    }
                    for (int s = k; s < Math.min(barW, k + 6); s++) {
                        if (up ? prof[s] > hiT : prof[s] < loT) { b = s; break; }
                    }
                    edge += (b - a);
                    ec++;
                }
            }
            if (ec > 0) {
                double e = edge / ec;
                System.out.printf("  边缘过渡 %.2f px（占模块宽 %.0f%%）%n", e, 100 * e / mod);
                edgeSum += e;
                edgeCnt++;
            }
        }

        System.out.println("\n=== 汇总 ===");
        System.out.printf("总游程 %d 个%n", totalRuns);
        System.out.println("按标称模块宽的偏差（0 = 完美）：");
        double allDev = 0;
        int allCnt = 0;
        for (int k = 1; k <= 4; k++) {
            if (devCnt[k] > 0) {
                double d = devSum[k] / devCnt[k];
                System.out.printf("  %d 模块宽：平均偏差 %.3f 模块（%.2f px），样本 %d%n",
                        k, d, d * (barsWidth() / 233.0), devCnt[k]);
                allDev += devSum[k];
                allCnt += devCnt[k];
            }
        }
        if (devCnt[0] > 0) {
            System.out.printf("  落在 1~4 之外：%d 个，平均偏差 %.2f 模块（说明有粘连或断裂）%n",
                    devCnt[0], devSum[0] / devCnt[0]);
        }
        if (allCnt > 0) {
            System.out.printf("  总体平均偏差 %.3f 模块 = %.2f px%n",
                    allDev / allCnt, allDev / allCnt * (barsWidth() / 233.0));
        }
        if (edgeCnt > 0) {
            System.out.printf("平均边缘过渡 %.2f px，模块宽 %.2f px → 过渡占模块 %.0f%%%n",
                    edgeSum / edgeCnt, barsWidth() / 233.0,
                    100 * (edgeSum / edgeCnt) / (barsWidth() / 233.0));
        }
    }

    static double barsWidth() {
        return 444;
    }

    /** 纵向找标签带：低饱和且亮 */
    static List<int[]> findBands() {
        int[] cnt = new int[H];
        for (int y = 0; y < H; y++) {
            int c = 0;
            for (int x = 0; x < W; x += 2) {
                int v = gray[y * W + x] & 0xFF;
                if (v > 140) c++;
            }
            cnt[y] = c;
        }
        // 用亮度不够区分（木桌也亮），改用「行内有大量纯黑」来定位条码带
        List<int[]> segs = new ArrayList<>();
        int start = -1, gap = 0;
        for (int y = 0; y < H; y++) {
            int dark = 0;
            for (int x = 0; x < W; x += 2) {
                if ((gray[y * W + x] & 0xFF) < 60) dark++;
            }
            boolean on = dark > W * 0.04;
            if (on) {
                if (start < 0) start = y;
                gap = 0;
            } else if (start >= 0) {
                if (++gap > 8) {
                    if (y - gap - start > 30) segs.add(new int[]{start, y - gap});
                    start = -1;
                    gap = 0;
                }
            }
        }
        if (start >= 0 && H - start > 30) segs.add(new int[]{start, H - 1});
        return segs;
    }

    static Result decodeBand(int[] band) {
        int y0 = Math.max(0, band[0] - 10), y1 = Math.min(H - 1, band[1] + 10);
        int bh = y1 - y0 + 1;
        byte[] sub = new byte[W * bh];
        for (int y = 0; y < bh; y++) {
            System.arraycopy(gray, (y0 + y) * W, sub, y * W, W);
        }
        Result r = decode(sub, W, bh, false);
        if (r == null) r = decode(sub, W, bh, true);
        return r;
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
