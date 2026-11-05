package com.pankscan.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.oned.Code128Writer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 提升空间探测台：把 v1.5 解码链的隐含假设逐个拆开量。
 *
 * 与 LowResTest 的分工：
 *  - LowResTest 是回归基线（条码固定在画面正中，只比算法链）；
 *  - GainProbe 是探索用（条码位置、倾斜可变），用来判断下一步该改哪里。
 *
 * 重点验证两件事：
 *  1) 固定扫描带 0.30~0.62 在条码偏上/偏下时会不会整块落空；
 *  2) 用「行梯度投影」先定位条码、再裁剪，能不能把位置自由度收回来。
 */
public class GainProbe {

    static final int UP_W = 720;
    static final int UP_H = 1280;
    static final int CAM_W = UP_H;
    static final int CAM_H = UP_W;
    static int BAR_H = 110;
    /** 传感器噪声强度（加减幅度），室内暗光下真实值远大于 2 */
    static int NOISE = 2;

    static final int PHASES = 6;
    static final float[] MODULES = {1.5f, 1.75f, 2.0f, 2.25f};
    static final float[] CENTERS = {0.22f, 0.34f, 0.46f, 0.60f, 0.72f};
    static final int[] BLUR = {1, 2};
    static final float[] TILT = {0f};

    /* ---------------- 模拟印刷 + 成像 ---------------- */

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

    static void blur(int[] img, int w, int h, int passes) {
        int[] k = {1, 4, 6, 4, 1};
        int[] tmp = new int[w * h];
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int s = 0;
                    for (int i = -2; i <= 2; i++) {
                        int xx = x + i;
                        if (xx < 0) xx = 0;
                        if (xx >= w) xx = w - 1;
                        s += img[row + xx] * k[i + 2];
                    }
                    tmp[row + x] = s >> 4;
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
                        s += tmp[yy * w + x] * k[i + 2];
                    }
                    img[row + x] = s >> 4;
                }
            }
        }
    }

    /**
     * @param barCy  条码中心在画面中的纵向位置（0~1）
     * @param deg    条码自身的旋转角（度），模拟手持倾斜
     */
    static byte[] render(String code, float modulePx, float phase, int passes,
                         float contrast, float barCy, float deg, long seed) {
        Random rnd = new Random(seed);
        int[] bg = new int[UP_W * UP_H];
        for (int y = 0; y < UP_H; y++) {
            int base = 228 + (int) (12 * Math.sin(y * Math.PI / UP_H));
            for (int x = 0; x < UP_W; x++) {
                bg[y * UP_W + x] = base + rnd.nextInt(7) - 3;
            }
        }

        byte[] row = renderRow(code, modulePx, phase);
        int rw = row.length;
        if (rw > UP_W - 24) return null;

        int left = (UP_W - rw) / 2;
        int cy = (int) (UP_H * barCy);
        int top = cy - BAR_H / 2;
        int blackLevel = (int) (255 - 255 * contrast);
        double tan = Math.tan(Math.toRadians(deg));

        for (int y = top; y < top + BAR_H; y++) {
            if (y < 0 || y >= UP_H) continue;
            int shift = (int) Math.round((y - cy) * tan);
            int rowBase = y * UP_W;
            for (int x = 0; x < rw; x++) {
                int dx = left + x + shift;
                if (dx < 0 || dx >= UP_W) continue;
                int g = row[x] & 0xFF;
                int v = 255 - (int) ((255 - blackLevel) * (255 - g) / 255f);
                v += rnd.nextInt(NOISE * 2 + 1) - NOISE;
                bg[rowBase + dx] = v;
            }
        }

        blur(bg, UP_W, UP_H, passes);

        byte[] out = new byte[UP_W * UP_H];
        for (int i = 0; i < out.length; i++) {
            int v = bg[i];
            out[i] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
        }
        return new ImageOps().rotate(out, UP_W, UP_H, false, 0);
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
        return decodeWith(buf, w, h, reader(hard), bin);
    }

    /** 只认 Code 128 的 reader：多码制并行时，EAN/UPC 会把 Code 128 的条纹误认成 13 位数字 */
    static MultiFormatReader reader128(boolean hard) {
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

    static String decodeWith(byte[] buf, int w, int h, MultiFormatReader r, int bin) {
        if (buf == null || w <= 0 || h <= 0 || r == null) return null;
        try {
            LuminanceSource src = new PlanarYUVLuminanceSource(buf, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(bin == ImageOps.BIN_GLOBAL
                    ? new GlobalHistogramBinarizer(src) : new HybridBinarizer(src));
            Result res = r.decodeWithState(bmp);
            return res == null ? null : res.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 行梯度投影定位：一维码所在的行，水平方向的灰度跳变极强，
     * 背景（桌面、纸面）几乎没有。逐行算 |p[x+2]-p[x]| 的均值，
     * 找到梯度最强的那一段连续区间，就是条码的上下边界。
     *
     * @return {top, bottom} 像素坐标（bottom 不含）；找不到返回 null
     */
    static int[] locateBar(byte[] up, int w, int h) {
        int step = 2;
        int rows = h / step;
        float[] g = new float[rows];
        int samples = Math.max(1, (w - 2) / 2);
        for (int r = 0; r < rows; r++) {
            int base = (r * step) * w;
            long s = 0;
            for (int x = 0; x + 2 < w; x += 2) {
                int d = (up[base + x + 2] & 0xFF) - (up[base + x] & 0xFF);
                s += d < 0 ? -d : d;
            }
            g[r] = s / (float) samples;
        }
        // 纵向 5 点平滑，压掉单行噪声
        float[] sm = new float[rows];
        for (int r = 0; r < rows; r++) {
            float a = 0f;
            int c = 0;
            for (int i = -2; i <= 2; i++) {
                int rr = r + i;
                if (rr >= 0 && rr < rows) {
                    a += g[rr];
                    c++;
                }
            }
            sm[r] = a / c;
        }
        float mx = 0f;
        int peak = 0;
        for (int r = 0; r < rows; r++) {
            if (sm[r] > mx) {
                mx = sm[r];
                peak = r;
            }
        }
        // 整幅都没有明显的竖线条纹，说明画面里没有码，或对比度低到无解
        if (mx < 10f) return null;
        float th = mx * 0.35f;
        int t = peak;
        int b = peak;
        while (t > 0 && sm[t - 1] >= th) t--;
        while (b < rows - 1 && sm[b + 1] >= th) b++;
        int top = t * step;
        int bot = Math.min(h, b * step + step);
        if (bot - top < 24) return null;
        return new int[]{top, bot};
    }

    /* ---------------- 策略 ---------------- */

    interface Strategy {
        String name();

        /** 0=失败 1=读对 2=读错 */
        int run(byte[] cam, String expect);
    }

    static int verdict(String got, String expect) {
        if (got == null) return 0;
        return expect.equals(got) ? 1 : 2;
    }

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

    /** 在一条已裁出的带上做 锐化 + 多档偏置 + 两种二值化 */
    static int tryBand(ImageOps ops, byte[] up, int top, int bot,
                       float biasFrom, float biasTo, int steps, String expect) {
        byte[] buf = ops.cropUpscale(up, UP_W, UP_H,
                0.15f, top / (float) UP_H, 0.85f, bot / (float) UP_H, 1);
        if (buf == null) return 0;
        int w = ops.outWidth();
        int h = ops.outHeight();
        if (w < 40 || h < 20) return 0;
        ops.sharpen(buf, w, h);
        int wrong = 0;
        for (int i = 0; i < steps; i++) {
            float bias = (steps <= 1) ? biasFrom
                    : biasFrom + (biasTo - biasFrom) * i / (steps - 1);
            int shift = (int) (bias * 255f);
            if (shift != 0) ops.shift(buf, w * h, shift);
            int v = verdict(decode(buf, w, h, true, ImageOps.BIN_HYBRID), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            v = verdict(decode(buf, w, h, true, ImageOps.BIN_GLOBAL), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            if (shift != 0) ops.shift(buf, w * h, -shift);
        }
        return wrong;
    }

    /** 固定带 */
    static Strategy fixedBand(final String name, final float t, final float b,
                              final float from, final float to, final int steps) {
        return new Strategy() {
            public String name() {
                return name;
            }

            public int run(byte[] cam, String expect) {
                int v = FULL.run(cam, expect);
                if (v != 0) return v;
                ImageOps ops = new ImageOps();
                byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                return tryBand(ops, up, (int) (UP_H * t), (int) (UP_H * b), from, to, steps, expect);
            }
        };
    }

    /** 先定位再裁剪 */
    static Strategy locatedBand(final String name, final float padFraction,
                                final float from, final float to, final int steps) {
        return new Strategy() {
            public String name() {
                return name;
            }

            public int run(byte[] cam, String expect) {
                int v = FULL.run(cam, expect);
                if (v != 0) return v;
                ImageOps ops = new ImageOps();
                byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                int[] box = locateBar(up, UP_W, UP_H);
                if (box == null) return 0;
                int pad = (int) ((box[1] - box[0]) * padFraction);
                int t = Math.max(0, box[0] - pad);
                int b = Math.min(UP_H, box[1] + pad);
                return tryBand(ops, up, t, b, from, to, steps, expect);
            }
        };
    }

    /* ---------------- 行直解：绕过 8x8 块二值化 ---------------- */

    static final Code128Reader CODE128 = new Code128Reader();
    static final Map<DecodeHintType, Object> HINTS_128 = new EnumMap<>(DecodeHintType.class);

    static {
        HINTS_128.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    }

    /**
     * 从 buf 里第 y 行开始，垂直平均 rows 行取一条「扫描线」，
     * 水平放大 up 倍后做单行二值化，再交给 Code128Reader。
     *
     * 三个区别点（都是针对低于 2 像素的模块）：
     *  - 垂直平均：条码每行的噪声独立，平均后信噪比更好，且不损失横向分辨率；
     *  - 水平上采样：模块只有 1.5 像素时，条在哪一列结束是「算出来」的而不是「取整」的，
     *    放大 4 倍等于把边缘位置精度也提了 4 倍，ZXing 数宽窄比才有依据；
     *  - 单行滑窗阈值：窗口 64 像素，比模块大一个数量级，模块不会被抹平，
     *    而 HybridBinarizer 的 8x8 块只装得下四五个模块。
     */
    static String decodeLine(byte[] buf, int w, int h, int y, int rows, int up,
                             int win, boolean adaptive) {
        int[] line = new int[w];
        for (int x = 0; x < w; x++) {
            int s = 0;
            for (int r = 0; r < rows; r++) {
                int yy = y + r;
                if (yy < 0) yy = 0;
                if (yy >= h) yy = h - 1;
                s += buf[yy * w + x] & 0xFF;
            }
            line[x] = s / rows;
        }

        int uw = w * up;
        int[] big;
        if (up == 1) {
            big = line;
        } else {
            big = new int[uw];
            for (int i = 0; i < uw; i++) {
                float sx = (i + 0.5f) / up - 0.5f;
                int x0 = (int) Math.floor(sx);
                float f = sx - x0;
                if (x0 < 0) {
                    x0 = 0;
                    f = 0;
                }
                int x1 = Math.min(w - 1, x0 + 1);
                big[i] = (int) (line[x0] + (line[x1] - line[x0]) * f);
            }
        }

        BitArray bits = new BitArray(uw);
        if (!adaptive) {
            int lo = 255;
            int hi = 0;
            for (int v : big) {
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            int th = (lo + hi) / 2;
            for (int i = 0; i < uw; i++) if (big[i] < th) bits.set(i);
        } else {
            int half = win / 2;
            for (int i = 0; i < uw; i++) {
                int lo = 255;
                int hi = 0;
                int a = i - half;
                if (a < 0) a = 0;
                int b = i + half;
                if (b > uw - 1) b = uw - 1;
                for (int j = a; j <= b; j++) {
                    int v = big[j];
                    if (v < lo) lo = v;
                    if (v > hi) hi = v;
                }
                if (hi - lo < 24) continue;          // 这一段没什么对比，别硬切
                if (big[i] * 2 < lo + hi) bits.set(i);
            }
        }

        try {
            Result r = CODE128.decodeRow(y, bits, HINTS_128);
            return r == null ? null : r.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在 [top,bottom) 里均匀取 lines 条扫描线，任何一条读对就算成功 */
    static int tryLines(byte[] buf, int w, int h, int top, int bot,
                        int lines, int avgRows, int up, int win,
                        boolean adaptive, String expect) {
        int span = bot - top;
        if (span < avgRows + 2) return 0;
        int wrong = 0;
        for (int i = 0; i < lines; i++) {
            int y = top + (int) ((i + 0.5f) * span / lines) - avgRows / 2;
            String got = decodeLine(buf, w, h, y, avgRows, up, win, adaptive);
            int v = verdict(got, expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
        }
        return wrong;
    }

    /** 定位 + 行直解 */
    static Strategy locatedLines(final String name, final float padFraction,
                                 final int lines, final int avgRows, final int up,
                                 final int win, final boolean adaptive) {
        return new Strategy() {
            public String name() {
                return name;
            }

            public int run(byte[] cam, String expect) {
                int v = FULL.run(cam, expect);
                if (v != 0) return v;
                ImageOps ops = new ImageOps();
                byte[] up2 = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                int[] box = locateBar(up2, UP_W, UP_H);
                if (box == null) return 0;
                int pad = (int) ((box[1] - box[0]) * padFraction);
                int t = Math.max(0, box[0] - pad);
                int b = Math.min(UP_H, box[1] + pad);
                return tryLines(up2, UP_W, UP_H, t, b, lines, avgRows, up, win, adaptive, expect);
            }
        };
    }

    public static void main(String[] args) {
        if (args.length > 0 && "lines".equals(args[0])) {
            runLines();
            return;
        }
        if (args.length > 0 && "diag".equals(args[0])) {
            runDiag();
            return;
        }
        if (args.length > 0 && "tilt".equals(args[0])) {
            runTilt();
            return;
        }
        if (args.length > 0 && "enhance".equals(args[0])) {
            runEnhanceChain();
            return;
        }
        if (args.length > 0 && "enhance2".equals(args[0])) {
            runEnhance2();
            return;
        }
        if (args.length > 0 && "sharp".equals(args[0])) {
            runSharp();
            return;
        }
        if (args.length > 0 && "wide".equals(args[0])) {
            runWide();
            return;
        }
        if (args.length > 0 && "final".equals(args[0])) {
            runFinal();
            return;
        }
        if (args.length > 0 && "blame".equals(args[0])) {
            runBlame();
            return;
        }
        runLocate();
    }

    /** 逐样本对照：纯整图带 vs 纯行直解（都不含全幅兜底），看清各自失效在哪类样本 */
    static void runDiag() {
        String code = "LXT261027AC0000030";
        System.out.println("样本对照（不含全幅兜底）：band=裁剪+锐化后整图解码；"
                + "line=单行二值化直解");
        System.out.printf("%-6s %-4s %-6s %-7s %-6s %-6s %-6s %-6s%n",
                "模块", "模糊", "相位", "跳变", "band", "band锐", "line1x", "line4x");
        System.out.println("------------------------------------------------------------");
        int bandOk = 0;
        int lineOk = 0;
        int lineOnly = 0;
        int bandOnly = 0;
        int n = 0;
        for (int passes : new int[]{1, 2}) {
            for (float m : new float[]{1.5f, 1.75f, 2.0f, 2.25f}) {
                for (int ph = 0; ph < PHASES; ph++) {
                    byte[] cam = render(code, m, ph / (float) PHASES, passes, 0.55f,
                            0.46f, 0f, 5501L * passes + (long) (m * 100) + ph);
                    if (cam == null) continue;
                    ImageOps ops = new ImageOps();
                    byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                    int[] box = locateBar(up, UP_W, UP_H);
                    if (box == null) {
                        System.out.printf("%-6.2f %-4d %-6.1f 定位失败%n", m, passes,
                                ph / (float) PHASES);
                        continue;
                    }
                    int pad = (int) ((box[1] - box[0]) * 0.10f);
                    int t = Math.max(0, box[0] - pad);
                    int b = Math.min(UP_H, box[1] + pad);

                    // 跳变次数（衡量这帧还剩多少细节）
                    int y = (box[0] + box[1]) / 2;
                    int lo = 255;
                    int hi = 0;
                    for (int x = 0; x < UP_W; x++) {
                        int v = up[y * UP_W + x] & 0xFF;
                        if (v < lo) lo = v;
                        if (v > hi) hi = v;
                    }
                    int th = (lo + hi) / 2;
                    int edges = 0;
                    for (int x = 1; x < UP_W; x++) {
                        if (((up[y * UP_W + x - 1] & 0xFF) < th)
                                != ((up[y * UP_W + x] & 0xFF) < th)) edges++;
                    }

                    ImageOps o1 = new ImageOps();
                    byte[] bd = o1.cropUpscale(up, UP_W, UP_H, 0.15f, t / (float) UP_H,
                            0.85f, b / (float) UP_H, 1);
                    int bw = o1.outWidth();
                    int bh = o1.outHeight();
                    boolean okBand = false;
                    boolean okBandSharp = false;
                    if (bd != null) {
                        okBand = code.equals(decode(bd, bw, bh, true, ImageOps.BIN_HYBRID));
                        o1.sharpen(bd, bw, bh);
                        okBandSharp = code.equals(decode(bd, bw, bh, true, ImageOps.BIN_HYBRID));
                    }
                    boolean okLine1 = false;
                    boolean okLine4 = false;
                    for (int i = 0; i < 7; i++) {
                        int yy = t + (int) ((i + 0.5f) * (b - t) / 7);
                        if (code.equals(decodeLine(up, UP_W, UP_H, yy, 1, 1, 64, false))) okLine1 = true;
                        if (code.equals(decodeLine(up, UP_W, UP_H, yy, 1, 4, 64, false))) okLine4 = true;
                    }
                    n++;
                    if (okBandSharp) bandOk++;
                    if (okLine1) lineOk++;
                    if (okLine1 && !okBandSharp) lineOnly++;
                    if (!okLine1 && okBandSharp) bandOnly++;
                    System.out.printf("%-6.2f %-4d %-6.1f %-7d %-6s %-6s %-6s %-6s%n",
                            m, passes, ph / (float) PHASES, edges,
                            okBand ? "√" : "×", okBandSharp ? "√" : "×",
                            okLine1 ? "√" : "×", okLine4 ? "√" : "×");
                }
            }
        }
        System.out.println("------------------------------------------------------------");
        System.out.printf("样本 %d：band+锐化 成功 %d，行直解1x 成功 %d%n", n, bandOk, lineOk);
        System.out.printf("只有行直解能解 %d 个，只有 band 能解 %d 个%n", lineOnly, bandOnly);
    }

    static void runLocate() {
        String code = "LXT261027AC0000030";

        List<Strategy> all = new ArrayList<>();
        all.add(FULL);
        all.add(fixedBand("带3档", 0.30f, 0.62f, -0.12f, 0.12f, 3));
        all.add(fixedBand("带5档", 0.30f, 0.62f, -0.24f, 0.24f, 5));
        all.add(locatedBand("定位3档", 0.10f, -0.12f, 0.12f, 3));
        all.add(locatedBand("定位5档", 0.10f, -0.24f, 0.24f, 5));

        System.out.println("条码位置固定性检验：条高 " + BAR_H + "px，画幅 " + UP_W + "x" + UP_H);
        System.out.println("「带」= 固定扫描带 0.30~0.62（v1.5 的做法）；「定位」= 行梯度投影后裁剪");
        System.out.println("单元格：读对/读错/总数");
        System.out.println();
        System.out.printf("%-8s %-6s", "中心", "模糊");
        for (Strategy s : all) System.out.printf(" %-12s", s.name());
        System.out.println();
        System.out.println("------------------------------------------------------------------------------");

        int[] sum = new int[all.size()];
        int[] bad = new int[all.size()];
        int frames = 0;

        // 先按「离中心距离」看趋势
        for (int pi = 0; pi < CENTERS.length; pi++) {
            float cy = CENTERS[pi];
            for (int passes : BLUR) {
                for (float m : MODULES) {
                    int[] ok = new int[all.size()];
                    int[] wg = new int[all.size()];
                    int n = 0;
                    for (int ph = 0; ph < PHASES; ph++) {
                        byte[] cam = render(code, m, ph / (float) PHASES, passes, 0.55f,
                                cy, 0f, 4177L * passes + (long) (m * 100) + ph + pi * 31L);
                        if (cam == null) continue;
                        n++;
                        for (int i = 0; i < all.size(); i++) {
                            int v = all.get(i).run(cam, code);
                            if (v == 1) ok[i]++;
                            if (v == 2) wg[i]++;
                        }
                    }
                    if (n == 0) continue;
                    frames += n;
                    System.out.printf("%-8s %-6d", String.format("%.2f", cy), passes);
                    for (int i = 0; i < all.size(); i++) {
                        System.out.printf(" %-12s", ok[i] + "/" + wg[i] + "/" + n);
                        sum[i] += ok[i];
                        bad[i] += wg[i];
                    }
                    System.out.println();
                }
            }
        }
        System.out.println("------------------------------------------------------------------------------");
        System.out.printf("%-15s", "合计/" + frames);
        for (int i = 0; i < all.size(); i++) System.out.printf(" %-12s", sum[i] + "/" + bad[i] + "/" + frames);
        System.out.println();
        System.out.printf("%-15s", "识别率");
        for (int i = 0; i < all.size(); i++) {
            System.out.printf(" %-12s", String.format("%.0f%%", 100.0 * sum[i] / frames));
        }
        System.out.println();

        // 定位精度单独看：条码真实上下边界 vs 定位结果
        System.out.println();
        System.out.println("定位精度（真实边界 / 定位边界，条高 110px）：");
        for (float cy : CENTERS) {
            byte[] cam = render(code, 2.0f, 0.3f, 1, 0.55f, cy, 0f, 99L + (long) (cy * 1000));
            if (cam == null) continue;
            ImageOps ops = new ImageOps();
            byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
            int[] box = locateBar(up, UP_W, UP_H);
            int rt = (int) (UP_H * cy) - BAR_H / 2;
            int rb = rt + BAR_H;
            if (box == null) {
                System.out.printf("  cy=%.2f 真实 %d~%d  定位失败%n", cy, rt, rb);
            } else {
                System.out.printf("  cy=%.2f 真实 %d~%d  定位 %d~%d  (差 %+d/%+d)%n",
                        cy, rt, rb, box[0], box[1], box[0] - rt, box[1] - rb);
            }
        }
    }

    /** 细化：锐化增益 × 降噪 在两种噪声水平下，按模块宽度分组看有没有副作用 */
    static void runEnhance2() {
        String code = "LXT261027AC0000030";
        final float[] B3 = {-0.12f, 0f, 0.12f};
        final float[] modules = {1.5f, 1.75f, 2.0f, 2.25f, 2.5f};

        // 标定：ImageOps.sharpen 的 (8c-s)>>2 等于 c + 1.0*(c-均值)，即本表的 g8
        String[] names = {"g8现行", "g7", "g6", "g5", "g4", "降噪3+g8"};
        final int[] gains = {8, 7, 6, 5, 4, 8};
        final int[] dn = {1, 1, 1, 1, 1, 3};
        Enhance[] enhs = new Enhance[names.length];
        for (int i = 0; i < names.length; i++) {
            final int g = gains[i];
            final int d = dn[i];
            enhs[i] = new Enhance() {
                public void apply(byte[] b, int w, int h, int f) {
                    denoiseV(b, w, h, d);
                    sharpenGain(b, w, h, g);
                }
            };
        }

        for (int noise : new int[]{2, 10}) {
            NOISE = noise;
            System.out.println();
            System.out.println("=== 噪声 ±" + noise + "（每格 读对/读错/总） ===");
            System.out.printf("%-8s", "模块px");
            for (String nm : names) System.out.printf(" %-11s", nm);
            System.out.println();

            int[] sum = new int[names.length];
            int[] bad = new int[names.length];
            int total = 0;
            for (float m : modules) {
                int[] ok = new int[names.length];
                int[] wg = new int[names.length];
                int n = 0;
                for (int passes : new int[]{1, 2}) {
                    for (int ph = 0; ph < PHASES; ph++) {
                        byte[] cam = render(code, m, ph / (float) PHASES, passes, 0.55f,
                                0.46f, 0f, 2609L * passes + (long) (m * 100) + ph + noise);
                        if (cam == null) continue;
                        ImageOps ops = new ImageOps();
                        byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                        int[] box = locateBar(up, UP_W, UP_H);
                        if (box == null) continue;
                        int pad = (int) ((box[1] - box[0]) * 0.10f);
                        int t = Math.max(0, box[0] - pad);
                        int b = Math.min(UP_H, box[1] + pad);
                        n++;
                        for (int i = 0; i < enhs.length; i++) {
                            int v = runEnhance(new ImageOps(), up, t, b, enhs[i], B3, code, ph);
                            if (v == 1) ok[i]++;
                            if (v == 2) wg[i]++;
                        }
                    }
                }
                total += n;
                System.out.printf("%-8.2f", m);
                for (int i = 0; i < names.length; i++) {
                    System.out.printf(" %-11s", ok[i] + "/" + wg[i] + "/" + n);
                    sum[i] += ok[i];
                    bad[i] += wg[i];
                }
                System.out.println();
            }
            System.out.printf("%-8s", "合计");
            for (int i = 0; i < names.length; i++) System.out.printf(" %-11s", sum[i] + "/" + bad[i] + "/" + total);
            System.out.println();
            System.out.printf("%-8s", "识别率");
            for (int i = 0; i < names.length; i++) {
                System.out.printf(" %-11s", String.format("%.0f%%", 100.0 * sum[i] / total));
            }
            System.out.println();
        }
    }

    /** 通用矩阵：策略 × 模块 × 模糊 */
    static void runMatrix(String title, String code, float[] modules, int[] blurs,
                          float contrast, int noise) {
        NOISE = noise;
        final float[] B3 = {-0.12f, 0f, 0.12f};

        String[] names = {"三档偏置", "单档0", "五档偏置", "三档+宽裁"};
        final int[] gains = {8, 8, 8, 8};
        final float[][] biases = {B3, new float[]{0f},
                new float[]{-0.24f, -0.12f, 0f, 0.12f, 0.24f}, B3};
        Enhance[] enhs = new Enhance[names.length];
        for (int i = 0; i < names.length; i++) {
            final int g = gains[i];
            enhs[i] = new Enhance() {
                public void apply(byte[] b, int w, int h, int f) {
                    sharpenGain(b, w, h, g);
                }
            };
        }

        System.out.println();
        System.out.println(title);
        System.out.println();
        System.out.printf("%-8s %-4s", "模块px", "模糊");
        for (String nm : names) System.out.printf(" %-11s", nm);
        System.out.println();

        int[] sum = new int[names.length];
        int[] bad = new int[names.length];
        long[] ns = new long[names.length];
        int total = 0;
        for (int passes : blurs) {
            for (float m : modules) {
                int[] ok = new int[names.length];
                int[] wg = new int[names.length];
                int n = 0;
                for (int ph = 0; ph < PHASES; ph++) {
                    byte[] cam = render(code, m, ph / (float) PHASES, passes, contrast,
                            0.46f, 0f, 1301L * passes + (long) (m * 100) + ph + noise);
                    if (cam == null) continue;
                    ImageOps ops = new ImageOps();
                    byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                    int[] box = locateBar(up, UP_W, UP_H);
                    if (box == null) continue;
                    int pad = (int) ((box[1] - box[0]) * 0.10f);
                    int t = Math.max(0, box[0] - pad);
                    int b = Math.min(UP_H, box[1] + pad);
                    n++;
                    for (int i = 0; i < enhs.length; i++) {
                        long t0 = System.nanoTime();
                        int v = runEnhance(new ImageOps(), up, t, b, enhs[i], biases[i], code, ph);
                        ns[i] += System.nanoTime() - t0;
                        if (v == 1) ok[i]++;
                        if (v == 2) wg[i]++;
                    }
                }
                if (n == 0) {
                    System.out.printf("%-8.2f %-4d  (条码超出画面，跳过)%n", m, passes);
                    continue;
                }
                total += n;
                System.out.printf("%-8.2f %-4d", m, passes);
                for (int i = 0; i < names.length; i++) {
                    System.out.printf(" %-11s", ok[i] + "/" + wg[i] + "/" + n);
                    sum[i] += ok[i];
                    bad[i] += wg[i];
                }
                System.out.println();
            }
        }
        System.out.printf("%-13s", "合计");
        for (int i = 0; i < names.length; i++) System.out.printf(" %-11s", sum[i] + "/" + bad[i] + "/" + total);
        System.out.println();
        System.out.printf("%-13s", "识别率");
        for (int i = 0; i < names.length; i++) {
            System.out.printf(" %-11s", String.format("%.0f%%", 100.0 * sum[i] / total));
        }
        System.out.println();
        System.out.printf("%-13s", "均耗时");
        for (int i = 0; i < names.length; i++) {
            System.out.printf(" %-11s", String.format("%.1fms", ns[i] / 1e6 / total));
        }
        System.out.println();
    }

    /** 水平裁剪到底切掉了什么：条码变宽时，0.15~0.85 的裁剪切边会不会致命 */
    static void runWide() {
        String code = "LXT2610";
        float[] modules = {3f, 4f, 5f, 6f};
        int[] blurs = {0, 1};
        NOISE = 2;
        float[] B3 = {-0.12f, 0f, 0.12f};

        Enhance g6 = new Enhance() {
            public void apply(byte[] b, int w, int h, int f) {
                sharpenGain(b, w, h, 6);
            }
        };

        System.out.println("水平裁剪范围的代价（短码 " + code + "，单行宽 = 自然宽 × 模块宽）");
        System.out.println("画面宽 " + UP_W + "px；窄裁 x=0.15~0.85（宽 "
                + (int) (UP_W * 0.70f) + "px），宽裁 x=0.02~0.98（宽 " + (int) (UP_W * 0.96f) + "px）");
        System.out.println();
        System.out.printf("%-8s %-4s %-8s %-13s %-13s%n", "模块px", "模糊", "单行宽", "窄裁g6三档", "宽裁g6三档");
        System.out.println("------------------------------------------------------------");

        int[] sum = new int[2];
        int[] bad = new int[2];
        int total = 0;
        for (int passes : blurs) {
            for (float m : modules) {
                int n = 0;
                int[] ok = new int[2];
                int[] wg = new int[2];
                int rw = (int) Math.ceil(renderRow(code, m, 0f).length);
                for (int ph = 0; ph < PHASES; ph++) {
                    byte[] cam = render(code, m, ph / (float) PHASES, passes, 0.72f,
                            0.46f, 0f, 8801L * passes + (long) (m * 10) + ph);
                    if (cam == null) continue;
                    ImageOps ops = new ImageOps();
                    byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                    int[] box = locateBar(up, UP_W, UP_H);
                    if (box == null) continue;
                    int pad = (int) ((box[1] - box[0]) * 0.10f);
                    int t = Math.max(0, box[0] - pad);
                    int b = Math.min(UP_H, box[1] + pad);
                    n++;
                    int v = runEnhance(new ImageOps(), up, t, b, g6, B3, code, ph, 0.15f, 0.85f);
                    if (v == 1) ok[0]++;
                    if (v == 2) wg[0]++;
                    v = runEnhance(new ImageOps(), up, t, b, g6, B3, code, ph, 0.02f, 0.98f);
                    if (v == 1) ok[1]++;
                    if (v == 2) wg[1]++;
                }
                if (n == 0) {
                    System.out.printf("%-8.2f %-4d %-8d (超出画面，跳过)%n", m, passes, rw);
                    continue;
                }
                total += n;
                System.out.printf("%-8.2f %-4d %-8d %-13s %-13s%n", m, passes, rw,
                        ok[0] + "/" + wg[0] + "/" + n, ok[1] + "/" + wg[1] + "/" + n);
                sum[0] += ok[0];
                sum[1] += ok[1];
                bad[0] += wg[0];
                bad[1] += wg[1];
            }
        }
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-18s %-13s %-13s%n", "合计/" + total,
                sum[0] + "/" + bad[0], sum[1] + "/" + bad[1]);
        System.out.printf("%-18s %-13s %-13s%n", "识别率",
                String.format("%.0f%%", 100.0 * sum[0] / total),
                String.format("%.0f%%", 100.0 * sum[1] / total));
    }

    static void runSharp() {
        // 短码：模块宽时仍能整条塞进画面，用来验证「本来就清楚」的标签有没有被锐化拖累
        runMatrix("清晰短码（9 字符）：模块 2~5px，不模糊 / 轻微模糊，对比度 0.72",
                "LXT261027", new float[]{2f, 3f, 4f, 5f}, new int[]{0, 1}, 0.72f, 2);
        // 长码：难读场景，看单档偏置能不能替代三档
        runMatrix("难读长码（18 字符）：模块 1.5~2.5px，模糊 1~2 遍，对比度 0.55",
                "LXT261027AC0000030",
                new float[]{1.5f, 1.75f, 2.0f, 2.25f, 2.5f}, new int[]{1, 2}, 0.55f, 2);
    }

    /** 追查那个读错的样本：不同锐化增益下各自解出什么 */
    static void runBlame() {
        String code = "LXT261027";
        float m = 2.0f;
        int passes = 1;
        float ph = 0.5f;
        float cy = 0.46f;
        long seed = 7039L * passes + (long) (m * 100) + 2 + 46;
        byte[] cam = render(code, m, ph, passes, 0.60f, cy, 0f, seed);
        if (cam == null) {
            System.out.println("渲染失败");
            return;
        }
        ImageOps ops = new ImageOps();
        byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
        int[] box = locateBar(up, UP_W, UP_H);
        System.out.println("目标 " + code + "，定位 " + (box == null ? "失败"
                : box[0] + "~" + box[1] + "（真实 533~643）"));
        float[] B3 = {-0.12f, 0f, 0.12f};

        for (int g = 0; g <= 10; g += 2) {
            StringBuilder sb = new StringBuilder();
            for (float bias : B3) {
                for (int bin : new int[]{ImageOps.BIN_HYBRID, ImageOps.BIN_GLOBAL}) {
                    ImageOps o = new ImageOps();
                    byte[] buf = o.cropUpscale(up, UP_W, UP_H, 0.02f,
                            (box[0] - 11) / (float) UP_H, 0.98f,
                            (box[1] + 11) / (float) UP_H, 1);
                    int w = o.outWidth();
                    int h = o.outHeight();
                    sharpenGain(buf, w, h, g);
                    int shift = (int) (bias * 255f);
                    if (shift != 0) o.shift(buf, w * h, shift);
                    String got = decode(buf, w, h, true, bin);
                    String only = decodeWith(buf, w, h, reader128(true), bin);
                    String tag = got == null ? "-"
                            : (got.equals(code) ? "对" : "错:" + got + "/128:" + (only == null ? "无" : only));
                    sb.append(String.format("%-34s", tag));
                }
            }
            System.out.printf("g=%-2d %s%n", g, sb);
        }
        System.out.println("（每档依次：bias 0/-0.12/+0.12 × Hybrid/Global）");
    }

    /* ---------------- 第五轮：现状 vs 改进，整体对照 ---------------- */

    static int tryBand2(ImageOps ops, byte[] up, float xl, float xr, int t, int b,
                        int gain, float[] biases, String expect) {
        byte[] buf = ops.cropUpscale(up, UP_W, UP_H, xl, t / (float) UP_H,
                xr, b / (float) UP_H, 1);
        if (buf == null) return 0;
        int w = ops.outWidth();
        int h = ops.outHeight();
        if (w < 40 || h < 20) return 0;
        sharpenGain(buf, w, h, gain);
        int wrong = 0;
        for (float bias : biases) {
            int shift = (int) (bias * 255f);
            if (shift != 0) ops.shift(buf, w * h, shift);
            int v = verdict(decode(buf, w, h, true, ImageOps.BIN_HYBRID), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            v = verdict(decode(buf, w, h, true, ImageOps.BIN_GLOBAL), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            if (shift != 0) ops.shift(buf, w * h, -shift);
        }
        return wrong;
    }

    /** 两阶段：先把 Code 128 在所有偏置档上试完，都不行才放开其他码制 */
    static int tryBand128First(ImageOps ops, byte[] up, float xl, float xr, int t, int b,
                               int gain, float[] biases, String expect) {
        byte[] buf = ops.cropUpscale(up, UP_W, UP_H, xl, t / (float) UP_H,
                xr, b / (float) UP_H, 1);
        if (buf == null) return 0;
        int w = ops.outWidth();
        int h = ops.outHeight();
        if (w < 40 || h < 20) return 0;
        sharpenGain(buf, w, h, gain);
        MultiFormatReader r128 = reader128(true);

        for (float bias : biases) {
            int shift = (int) (bias * 255f);
            if (shift != 0) ops.shift(buf, w * h, shift);
            int v = verdict(decodeWith(buf, w, h, r128, ImageOps.BIN_HYBRID), expect);
            if (v == 1) return 1;
            v = verdict(decodeWith(buf, w, h, r128, ImageOps.BIN_GLOBAL), expect);
            if (v == 1) return 1;
            if (shift != 0) ops.shift(buf, w * h, -shift);
        }

        int wrong = 0;
        for (float bias : biases) {
            int shift = (int) (bias * 255f);
            if (shift != 0) ops.shift(buf, w * h, shift);
            int v = verdict(decode(buf, w, h, true, ImageOps.BIN_HYBRID), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            v = verdict(decode(buf, w, h, true, ImageOps.BIN_GLOBAL), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            if (shift != 0) ops.shift(buf, w * h, -shift);
        }
        return wrong;
    }

    /** 业务格式校验：SN 的字符集和长度是已知的，不符合的直接丢弃，继续扫 */
    static boolean fmtOk(String got, String expect) {
        if (got == null || got.length() != expect.length()) return false;
        for (int i = 0; i < got.length(); i++) {
            char c = got.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** 改进方案 + Code128 优先 + 格式校验 */
    static int tryBandFmt(ImageOps ops, byte[] up, float xl, float xr, int t, int b,
                          int gain, float[] biases, String expect) {
        byte[] buf = ops.cropUpscale(up, UP_W, UP_H, xl, t / (float) UP_H,
                xr, b / (float) UP_H, 1);
        if (buf == null) return 0;
        int w = ops.outWidth();
        int h = ops.outHeight();
        if (w < 40 || h < 20) return 0;
        sharpenGain(buf, w, h, gain);
        MultiFormatReader r128 = reader128(true);

        int wrong = 0;
        for (int phase = 0; phase < 2; phase++) {
            MultiFormatReader r = phase == 0 ? r128 : null;
            for (float bias : biases) {
                int shift = (int) (bias * 255f);
                if (shift != 0) ops.shift(buf, w * h, shift);
                for (int bin : new int[]{ImageOps.BIN_HYBRID, ImageOps.BIN_GLOBAL}) {
                    String got = r == null ? decode(buf, w, h, true, bin)
                            : decodeWith(buf, w, h, r, bin);
                    if (!fmtOk(got, expect)) continue;
                    int v = verdict(got, expect);
                    if (v == 1) return 1;
                    if (v == 2) wrong = 2;
                }
                if (shift != 0) ops.shift(buf, w * h, -shift);
            }
        }
        return wrong;
    }

    static void runFinal() {
        final float[] B3 = {-0.12f, 0f, 0.12f};
        String[] codes = {"LXT261027", "LXT261027AC0000030"};
        float[] modules = {2f, 3f, 4f};
        float[] centers = {0.25f, 0.46f, 0.68f};
        NOISE = 2;

        System.out.println("现状（v1.5）与改进方案对照，同一批样本、同一份全幅兜底");
        System.out.println("现状：x 0.15~0.85，y 固定 0.30~0.62，锐化 g2，三档偏置");
        System.out.println("改进：x 0.02~0.98，y 行梯度定位 ±10%，锐化不变，三档偏置");
        System.out.println();

        int[] ok = new int[4];
        int[] bad = new int[4];
        long[] ns = new long[4];
        int total = 0;
        int[] okPos = new int[6];
        int[] okPos2 = new int[6];
        int[] okPos3 = new int[6];
        int[] okPos4 = new int[6];
        int[] nPos = new int[6];

        for (String code : codes) {
            for (float m : modules) {
                for (int ci = 0; ci < centers.length; ci++) {
                    for (int passes : new int[]{1, 2}) {
                        for (int ph = 0; ph < 4; ph++) {
                            byte[] cam = render(code, m, ph * 0.25f, passes, 0.60f,
                                    centers[ci], 0f, 7039L * passes + (long) (m * 100) + ph
                                            + (long) (centers[ci] * 100));
                            if (cam == null) continue;
                            total++;
                            nPos[ci]++;

                            long t0 = System.nanoTime();
                            int a = FULL.run(cam, code);
                            if (a == 0) {
                                ImageOps o1 = new ImageOps();
                                byte[] up = o1.rotate(cam, CAM_W, CAM_H, true, 0);
                                a = tryBand2(o1, up, 0.15f, 0.85f,
                                        (int) (UP_H * 0.30f), (int) (UP_H * 0.62f), 8, B3, code);
                            }
                            ns[0] += System.nanoTime() - t0;
                            if (a == 1) {
                                ok[0]++;
                                okPos[ci]++;
                            }
                            if (a == 2) {
                                bad[0]++;
                                System.out.printf("  [!] 现状读错 code=%s m=%.2f cy=%.2f blur=%d ph=%.2f%n",
                                        code, m, centers[ci], passes, ph * 0.25f);
                            }

                            t0 = System.nanoTime();
                            int c = FULL.run(cam, code);
                            if (c == 0) {
                                ImageOps o2 = new ImageOps();
                                byte[] up = o2.rotate(cam, CAM_W, CAM_H, true, 0);
                                int[] box = locateBar(up, UP_W, UP_H);
                                if (box != null) {
                                    int pad = (int) ((box[1] - box[0]) * 0.10f);
                                    c = tryBand2(o2, up, 0.02f, 0.98f,
                                            Math.max(0, box[0] - pad),
                                            Math.min(UP_H, box[1] + pad), 8, B3, code);
                                }
                            }
                            ns[1] += System.nanoTime() - t0;
                            if (c == 1) {
                                ok[1]++;
                                okPos2[ci]++;
                            }
                            if (c == 2) {
                                bad[1]++;
                                System.out.printf("  [!] 改进读错 code=%s m=%.2f cy=%.2f blur=%d ph=%.2f%n",
                                        code, m, centers[ci], passes, ph * 0.25f);
                            }

                            // 改进 + Code128 优先
                            t0 = System.nanoTime();
                            int d = FULL.run(cam, code);
                            if (d == 0) {
                                ImageOps o3 = new ImageOps();
                                byte[] up = o3.rotate(cam, CAM_W, CAM_H, true, 0);
                                int[] box3 = locateBar(up, UP_W, UP_H);
                                if (box3 != null) {
                                    int pad = (int) ((box3[1] - box3[0]) * 0.10f);
                                    d = tryBand128First(o3, up, 0.02f, 0.98f,
                                            Math.max(0, box3[0] - pad),
                                            Math.min(UP_H, box3[1] + pad), 8, B3, code);
                                }
                            }
                            ns[2] += System.nanoTime() - t0;
                            if (d == 1) {
                                ok[2]++;
                                okPos3[ci]++;
                            }
                            if (d == 2) {
                                bad[2]++;
                                System.out.printf("  [!] 128优先读错 code=%s m=%.2f cy=%.2f blur=%d ph=%.2f%n",
                                        code, m, centers[ci], passes, ph * 0.25f);
                            }

                            // 改进 + 128优先 + 格式校验
                            t0 = System.nanoTime();
                            int e = FULL.run(cam, code);
                            if (e == 0) {
                                ImageOps o4 = new ImageOps();
                                byte[] up = o4.rotate(cam, CAM_W, CAM_H, true, 0);
                                int[] box4 = locateBar(up, UP_W, UP_H);
                                if (box4 != null) {
                                    int pad = (int) ((box4[1] - box4[0]) * 0.10f);
                                    e = tryBandFmt(o4, up, 0.02f, 0.98f,
                                            Math.max(0, box4[0] - pad),
                                            Math.min(UP_H, box4[1] + pad), 8, B3, code);
                                }
                            }
                            ns[3] += System.nanoTime() - t0;
                            if (e == 1) {
                                ok[3]++;
                                okPos4[ci]++;
                            }
                            if (e == 2) {
                                bad[3]++;
                                System.out.printf("  [!] 校验读错 code=%s m=%.2f cy=%.2f%n",
                                        code, m, centers[ci]);
                            }
                        }
                    }
                }
            }
        }

        System.out.println("--------------------------------------------------");
        String[] nm = {"现状", "改进", "改进+128优先", "改进+校验"};
        for (int i = 0; i < 4; i++) {
            System.out.printf("%-14s 读对 %3d/%d  读错 %d  识别率 %.0f%%  均耗时 %.1fms%n",
                    nm[i], ok[i], total, bad[i],
                    100.0 * ok[i] / total, ns[i] / 1e6 / total);
        }
        System.out.println();
        System.out.println("按条码位置（0.25 / 0.46 在画面偏上、居中；0.68 偏下）：");
        for (int ci = 0; ci < centers.length; ci++) {
            System.out.printf("  位置 %.2f：现状 %3.0f%%  改进 %3.0f%%  128优先 %3.0f%%  校验 %3.0f%%（%d 样本）%n",
                    centers[ci],
                    100.0 * okPos[ci] / Math.max(1, nPos[ci]),
                    100.0 * okPos2[ci] / Math.max(1, nPos[ci]),
                    100.0 * okPos3[ci] / Math.max(1, nPos[ci]),
                    100.0 * okPos4[ci] / Math.max(1, nPos[ci]),
                    nPos[ci]);
        }
    }

    /* ---------------- 第四轮：增强链组合 ---------------- */

    /** 垂直方向平均 rows 行（条码在垂直方向是恒定的，平均只降噪、不损横向细节） */
    static void denoiseV(byte[] buf, int w, int h, int rows) {
        if (rows <= 1) return;
        int n = w * h;
        byte[] src = new byte[n];
        System.arraycopy(buf, 0, src, 0, n);
        int half = rows / 2;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int s = 0;
                int c = 0;
                for (int i = -half; i <= half; i++) {
                    int yy = y + i;
                    if (yy < 0) yy = 0;
                    if (yy >= h) yy = h - 1;
                    s += src[yy * w + x] & 0xFF;
                    c++;
                }
                buf[row + x] = (byte) (s / c);
            }
        }
    }

    /**
     * 可调增益锐化：out = ((1+g)c - g*(左+右+上+下)/4)，g 以 1/8 为单位。
     * ImageOps.sharpen 相当于 g=2。
     */
    static void sharpenGain(byte[] buf, int w, int h, int g) {
        if (g == 0) return;
        int n = w * h;
        byte[] src = new byte[n];
        System.arraycopy(buf, 0, src, 0, n);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int up = y == 0 ? row : row - w;
            int dn = y == h - 1 ? row : row + w;
            for (int x = 0; x < w; x++) {
                int left = x == 0 ? x : x - 1;
                int right = x == w - 1 ? x : x + 1;
                int c = src[row + x] & 0xFF;
                int s = (src[row + left] & 0xFF) + (src[row + right] & 0xFF)
                        + (src[up + x] & 0xFF) + (src[dn + x] & 0xFF);
                // v = c + g*(c - 邻域均值)/8
                int mean = s / 4;
                int v = c + (g * (c - mean) >> 3);
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                buf[row + x] = (byte) v;
            }
        }
    }

    /** 只沿水平方向锐化：不放大垂直噪声，专治横向边缘发虚 */
    static void sharpenH(byte[] buf, int w, int h, int g) {
        if (g == 0) return;
        int n = w * h;
        byte[] src = new byte[n];
        System.arraycopy(buf, 0, src, 0, n);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int left = x == 0 ? x : x - 1;
                int right = x == w - 1 ? x : x + 1;
                int c = src[row + x] & 0xFF;
                int mean = ((src[row + left] & 0xFF) + (src[row + right] & 0xFF)) / 2;
                int v = c + (g * (c - mean) >> 3);
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                buf[row + x] = (byte) v;
            }
        }
    }

    interface Enhance {
        void apply(byte[] buf, int w, int h, int frameIdx);
    }

    /** 一条完整的增强 + 解码方案 */
    static int runEnhance(ImageOps ops, byte[] up, int t, int b,
                          Enhance enh, float[] biases, String expect, int frameIdx) {
        return runEnhance(ops, up, t, b, enh, biases, expect, frameIdx, 0.15f, 0.85f);
    }

    static int runEnhance(ImageOps ops, byte[] up, int t, int b,
                          Enhance enh, float[] biases, String expect, int frameIdx,
                          float xl, float xr) {
        byte[] buf = ops.cropUpscale(up, UP_W, UP_H, xl, t / (float) UP_H,
                xr, b / (float) UP_H, 1);
        if (buf == null) return 0;
        int w = ops.outWidth();
        int h = ops.outHeight();
        if (w < 40 || h < 20) return 0;
        enh.apply(buf, w, h, frameIdx);
        int wrong = 0;
        for (float bias : biases) {
            int shift = (int) (bias * 255f);
            if (shift != 0) ops.shift(buf, w * h, shift);
            int v = verdict(decode(buf, w, h, true, ImageOps.BIN_HYBRID), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            v = verdict(decode(buf, w, h, true, ImageOps.BIN_GLOBAL), expect);
            if (v == 1) return 1;
            if (v == 2) wrong = 2;
            if (shift != 0) ops.shift(buf, w * h, -shift);
        }
        return wrong;
    }

    static void runEnhanceChain() {
        String code = "LXT261027AC0000030";
        final float[] B3 = {-0.12f, 0f, 0.12f};

        String[] names = {
                "现方案", "垂直降噪3", "垂直降噪5", "降噪3+横向锐",
                "强锐化g4", "弱锐化g1", "拉伸前置", "锐化多档",
        };
        Enhance[] enhs = {
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        sharpenGain(b, w, h, 2);
                    }
                },
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        denoiseV(b, w, h, 3);
                        sharpenGain(b, w, h, 2);
                    }
                },
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        denoiseV(b, w, h, 5);
                        sharpenGain(b, w, h, 2);
                    }
                },
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        denoiseV(b, w, h, 3);
                        sharpenH(b, w, h, 4);
                    }
                },
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        sharpenGain(b, w, h, 4);
                    }
                },
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        sharpenGain(b, w, h, 1);
                    }
                },
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        ImageOps o = new ImageOps();
                        o.stretch(b, w * h);
                        sharpenGain(b, w, h, 2);
                    }
                },
                new Enhance() {
                    public void apply(byte[] b, int w, int h, int f) {
                        int[] gs = {1, 2, 4};
                        sharpenGain(b, w, h, gs[f % gs.length]);
                    }
                },
        };

        System.out.println("增强链组合（定位后裁剪，纯解码不含全幅兜底）");
        System.out.println("样本：模块 1.5~2.5px × 模糊 1~2 遍 × 6 个相位 = 60");
        System.out.println();
        System.out.printf("%-16s %-14s %-8s%n", "方案", "读对/读错/总", "识别率");
        System.out.println("----------------------------------------------");

        int[] sum = new int[enhs.length];
        int[] bad = new int[enhs.length];
        int n = 0;
        for (int passes : new int[]{1, 2}) {
            for (float m : new float[]{1.5f, 1.75f, 2.0f, 2.25f, 2.5f}) {
                for (int ph = 0; ph < PHASES; ph++) {
                    byte[] cam = render(code, m, ph / (float) PHASES, passes, 0.55f,
                            0.46f, 0f, 3301L * passes + (long) (m * 100) + ph);
                    if (cam == null) continue;
                    ImageOps ops = new ImageOps();
                    byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);
                    int[] box = locateBar(up, UP_W, UP_H);
                    if (box == null) continue;
                    int pad = (int) ((box[1] - box[0]) * 0.10f);
                    int t = Math.max(0, box[0] - pad);
                    int b = Math.min(UP_H, box[1] + pad);
                    n++;
                    for (int i = 0; i < enhs.length; i++) {
                        int v = runEnhance(new ImageOps(), up, t, b, enhs[i], B3, code, ph);
                        if (v == 1) sum[i]++;
                        if (v == 2) bad[i]++;
                    }
                }
            }
        }
        for (int i = 0; i < enhs.length; i++) {
            System.out.printf("%-16s %-14s %-8s%n", names[i],
                    sum[i] + "/" + bad[i] + "/" + n,
                    String.format("%.0f%%", 100.0 * sum[i] / n));
        }
    }

    /* ---------------- 第三轮：倾斜与远近（定位方案能不能落地） ---------------- */

    static void runTilt() {
        String code = "LXT261027AC0000030";
        float[] tilts = {0f, 3f, 6f};
        int[] heights = {60, 110, 200};
        float[] centers = {0.35f, 0.60f};
        float[] modules = {1.75f, 2.25f};
        float[] pads = {0.05f, 0.10f, 0.20f};

        System.out.println("倾斜 / 条高变化下，定位方案是否还站得住");
        System.out.println("每个倾斜角 72 个样本（3 种条高 × 2 个位置 × 2 种模块 × 2 档模糊 × 2 相位）");
        System.out.println();
        System.out.printf("%-6s %-12s %-12s %-12s %-12s %-12s%n",
                "倾斜", "固定带3档", "定位pad5%", "定位pad10%", "定位pad20%", "定位+行直解");
        System.out.println("--------------------------------------------------------------------");

        for (float tilt : tilts) {
            int[] ok = new int[5];
            int[] wg = new int[5];
            int n = 0;
            int locErr = 0;
            int locMiss = 0;

            for (int hgt : heights) {
                BAR_H = hgt;
                for (float cy : centers) {
                    for (float m : modules) {
                        for (int passes : new int[]{1, 2}) {
                            for (int ph = 0; ph < 2; ph++) {
                                byte[] cam = render(code, m, ph * 0.3f, passes, 0.55f,
                                        cy, tilt, 911L * passes + (long) (m * 100) + ph
                                                + (long) (tilt * 7) + hgt);
                                if (cam == null) continue;
                                n++;
                                ImageOps ops = new ImageOps();
                                byte[] up = ops.rotate(cam, CAM_W, CAM_H, true, 0);

                                int rt = (int) (UP_H * cy) - hgt / 2;
                                int rb = rt + hgt;
                                int[] box = locateBar(up, UP_W, UP_H);
                                if (box == null) {
                                    locMiss++;
                                } else {
                                    locErr += Math.abs(box[0] - rt) + Math.abs(box[1] - rb);
                                }

                                // 固定带（v1.5）
                                int v = FULL.run(cam, code);
                                if (v == 1) ok[0]++;
                                else if (v == 2) wg[0]++;
                                if (v == 0) {
                                    v = tryBand(ops, up, (int) (UP_H * 0.30f), (int) (UP_H * 0.62f),
                                            -0.12f, 0.12f, 3, code);
                                    if (v == 1) ok[0]++;
                                    else if (v == 2) wg[0]++;
                                }

                                // 定位 + 三档偏置，pad 三档
                                for (int pi = 0; pi < pads.length; pi++) {
                                    int idx = 1 + pi;
                                    if (box == null) continue;
                                    int pad = (int) ((box[1] - box[0]) * pads[pi]);
                                    int t = Math.max(0, box[0] - pad);
                                    int b = Math.min(UP_H, box[1] + pad);
                                    int v2 = tryBand(ops, up, t, b, -0.12f, 0.12f, 3, code);
                                    if (v2 == 1) ok[idx]++;
                                    else if (v2 == 2) wg[idx]++;
                                }

                                // 定位 + 行直解
                                if (box != null) {
                                    int pad = (int) ((box[1] - box[0]) * 0.10f);
                                    int t = Math.max(0, box[0] - pad);
                                    int b = Math.min(UP_H, box[1] + pad);
                                    int v3 = tryLines(up, UP_W, UP_H, t, b, 7, 1, 1, 64, false, code);
                                    if (v3 == 1) ok[4]++;
                                    else if (v3 == 2) wg[4]++;
                                }
                            }
                        }
                    }
                }
            }
            System.out.printf("%-6.0f", tilt);
            for (int i = 0; i < ok.length; i++) {
                System.out.printf(" %-12s", ok[i] + "/" + wg[i] + "/" + n);
            }
            System.out.printf("   定位误差均值 %.1fpx，失败 %d%n",
                    locErr / (double) Math.max(1, n - locMiss), locMiss);
        }
    }

    /* ---------------- 第二轮：行直解 ---------------- */

    static void runLines() {
        String code = "LXT261027AC0000030";
        float[] modules = {1.5f, 1.75f, 2.0f};
        int[] blurs = {1, 2};

        List<Strategy> all = new ArrayList<>();
        all.add(locatedBand("整图定位3档", 0.10f, -0.12f, 0.12f, 3));
        all.add(locatedLines("行1x全局", 0.10f, 7, 2, 1, 64, false));
        all.add(locatedLines("行4x全局", 0.10f, 7, 2, 4, 64, false));
        all.add(locatedLines("行4x滑窗64", 0.10f, 7, 2, 4, 64, true));
        all.add(locatedLines("行2x滑窗64", 0.10f, 7, 2, 2, 64, true));
        all.add(locatedLines("行4x滑窗96", 0.10f, 7, 2, 4, 96, true));

        System.out.println("行直解对比：条码居中，条高 " + BAR_H + "px，7 条扫描线、垂直平均 2 行");
        System.out.println("单元格：读对/读错/总数");
        System.out.println();
        System.out.printf("%-7s %-4s", "模块px", "模糊");
        for (Strategy s : all) System.out.printf(" %-13s", s.name());
        System.out.println();
        System.out.println("------------------------------------------------------------------------");

        int[] sum = new int[all.size()];
        int[] bad = new int[all.size()];
        int frames = 0;
        long[] nanos = new long[all.size()];

        for (int passes : blurs) {
            for (float m : modules) {
                int[] ok = new int[all.size()];
                int[] wg = new int[all.size()];
                int n = 0;
                for (int ph = 0; ph < PHASES; ph++) {
                    byte[] cam = render(code, m, ph / (float) PHASES, passes, 0.55f,
                            0.46f, 0f, 5501L * passes + (long) (m * 100) + ph);
                    if (cam == null) continue;
                    n++;
                    for (int i = 0; i < all.size(); i++) {
                        long t0 = System.nanoTime();
                        int v = all.get(i).run(cam, code);
                        nanos[i] += System.nanoTime() - t0;
                        if (v == 1) ok[i]++;
                        if (v == 2) wg[i]++;
                    }
                }
                if (n == 0) continue;
                frames += n;
                System.out.printf("%-7.2f %-4d", m, passes);
                for (int i = 0; i < all.size(); i++) {
                    System.out.printf(" %-13s", ok[i] + "/" + wg[i] + "/" + n);
                    sum[i] += ok[i];
                    bad[i] += wg[i];
                }
                System.out.println();
            }
        }
        System.out.println("------------------------------------------------------------------------");
        System.out.printf("%-12s", "合计/" + frames);
        for (int i = 0; i < all.size(); i++) System.out.printf(" %-13s", sum[i] + "/" + bad[i] + "/" + frames);
        System.out.println();
        System.out.printf("%-12s", "识别率");
        for (int i = 0; i < all.size(); i++) {
            System.out.printf(" %-13s", String.format("%.0f%%", 100.0 * sum[i] / frames));
        }
        System.out.println();
        System.out.printf("%-12s", "单帧均耗时");
        for (int i = 0; i < all.size(); i++) {
            System.out.printf(" %-13s", String.format("%.1fms", nanos[i] / 1e6 / frames));
        }
        System.out.println();
    }
}
