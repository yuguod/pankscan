package com.pankscan.app;

import java.util.Arrays;

/**
 * 扫码用的纯 Java 图像处理。
 *
 * 这里刻意不引用任何 Android 类，目的是可以在桌面上用 test/LowResTest.java
 * 直接跑回归测试——条码方向、放大、增强这类逻辑一旦写错，表现就是
 * 「有画面但永远扫不出」，光靠真机试错代价太大。
 *
 * 所有缓冲区都循环复用，避免每帧分配上兆字节把 GC 拖起来。
 */
public final class ImageOps {

    /** 局部自适应二值化：光照不均时更稳 */
    public static final int BIN_HYBRID = 0;
    /** 全局直方图二值化：低对比度、小模块的印刷条码往往只有它能啃下来 */
    public static final int BIN_GLOBAL = 1;

    private final byte[][] rotBuf = new byte[2][];
    private byte[] cropBuf = new byte[0];
    private byte[] copyBuf = new byte[0];
    private int[] xs0 = new int[0];
    private int[] xs1 = new int[0];
    private int[] xf = new int[0];
    /** 采样表是按「裁剪左边界 + 宽度 + 倍数」算出来的，三者变了必须重建 */
    private int xsL = Integer.MIN_VALUE;
    private int xsCw = -1;
    private int xsScale = -1;
    private int xsDw = -1;

    private int[] rowE = new int[0];
    private int[] rowS = new int[0];
    private int[] colE = new int[0];

    private int outW;
    private int outH;

    /** cropUpscale 产出的宽 */
    public int outWidth() {
        return outW;
    }

    /** cropUpscale 产出的高 */
    public int outHeight() {
        return outH;
    }

    /**
     * 把 Y 平面旋转 90 度，结果宽高互换，所以行跨度必须用 h（新宽度）。
     * 写成 w 的话宽高不等时会整幅错位，条码永远读不出来。
     *
     * @param slot 0/1 两个缓冲区，同一帧里要转两个方向时用不同的 slot
     */
    public byte[] rotate(byte[] src, int w, int h, boolean cw, int slot) {
        int n = w * h;
        if (rotBuf[slot] == null || rotBuf[slot].length != n) rotBuf[slot] = new byte[n];
        byte[] out = rotBuf[slot];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            if (cw) {
                for (int x = 0; x < w; x++) out[x * h + (h - 1 - y)] = src[row + x];
            } else {
                for (int x = 0; x < w; x++) out[(w - 1 - x) * h + y] = src[row + x];
            }
        }
        return out;
    }

    /**
     * 按比例裁出中间一块，并按 scale 倍双线性放大。
     *
     * 放大不是为了「凭空变清晰」，而是为了让 ZXing 的 HybridBinarizer 好受一点：
     * 它按 8x8 小块算局部阈值，模块只有 1~2 像素时一个块里塞了四五个模块，
     * 阈值出来整块被抹平，条就没了。放大到模块 4~6 像素后，一块只盖住一个多模块，
     * 阈值才对得上。
     *
     * @param fl/ft/fr/fb 裁剪范围，取 0~1 的比例
     * @param scale       1 = 只裁剪，2 = 同时放大一倍
     */
    public byte[] cropUpscale(byte[] src, int sw, int sh,
                              float fl, float ft, float fr, float fb, int scale) {
        int l = (int) (sw * fl);
        int t = (int) (sh * ft);
        int r = (int) (sw * fr);
        int b = (int) (sh * fb);
        return cropRect(src, sw, sh, l, t, r, b, scale);
    }

    /**
     * 按像素坐标裁一块出来（cropUpscale 的底子），给「自动定位到的条码框」用。
     *
     * @param l/t/r/b 像素边界，右/下不含
     */
    public byte[] cropRect(byte[] src, int sw, int sh,
                           int l, int t, int r, int b, int scale) {
        if (src == null || sw <= 0 || sh <= 0) {
            outW = 0;
            outH = 0;
            return null;
        }
        if (l < 0) l = 0;
        if (t < 0) t = 0;
        if (r > sw) r = sw;
        if (b > sh) b = sh;
        int cw = r - l;
        int ch = b - t;
        if (cw < 16 || ch < 16) {
            outW = 0;
            outH = 0;
            return null;
        }
        if (scale < 1) scale = 1;

        int dw = cw * scale;
        int dh = ch * scale;
        if (cropBuf.length != dw * dh) cropBuf = new byte[dw * dh];
        byte[] out = cropBuf;
        outW = dw;
        outH = dh;

        if (scale == 1) {
            // 原样搬一块出来，行来回拷，比逐像素算快得多
            for (int y = 0; y < ch; y++) {
                System.arraycopy(src, (t + y) * sw + l, out, y * dw, cw);
            }
            return out;
        }

        // x 方向的取样点和权重是固定的，先算一次表
        if (xs0.length != dw || xsL != l || xsCw != cw || xsScale != scale) {
            xs0 = new int[dw];
            xs1 = new int[dw];
            xf = new int[dw];
            int maxX = l + cw - 1;
            for (int dx = 0; dx < dw; dx++) {
                float sx = l + (dx + 0.5f) / scale - 0.5f;
                if (sx < l) sx = l;
                if (sx > maxX) sx = maxX;
                int x0 = (int) sx;
                float f = sx - x0;
                int x1 = x0 + 1;
                if (x1 > maxX) x1 = maxX;
                xs0[dx] = x0;
                xs1[dx] = x1;
                xf[dx] = (int) (f * 256f);
            }
            xsL = l;
            xsCw = cw;
            xsScale = scale;
            xsDw = dw;
        }

        int maxY = t + ch - 1;
        for (int dy = 0; dy < dh; dy++) {
            float sy = t + (dy + 0.5f) / scale - 0.5f;
            if (sy < t) sy = t;
            if (sy > maxY) sy = maxY;
            int y0 = (int) sy;
            int fy = (int) ((sy - y0) * 256f);
            int y1 = y0 + 1;
            if (y1 > maxY) y1 = maxY;
            int row0 = y0 * sw;
            int row1 = y1 * sw;
            int dst = dy * dw;
            for (int dx = 0; dx < dw; dx++) {
                int a = xs0[dx];
                int c = xs1[dx];
                int fx = xf[dx];
                int p00 = src[row0 + a] & 0xFF;
                int p01 = src[row0 + c] & 0xFF;
                int p10 = src[row1 + a] & 0xFF;
                int p11 = src[row1 + c] & 0xFF;
                int top = p00 + (((p01 - p00) * fx) >> 8);
                int bot = p10 + (((p11 - p10) * fx) >> 8);
                out[dst + dx] = (byte) (top + (((bot - top) * fy) >> 8));
            }
        }
        return out;
    }

    /**
     * 直方图百分位对比度拉伸（原地）。
     * 热敏打印的标签经常是「灰底灰条」，黑不黑白不白，拉伸到满量程后二值化才分得开。
     */
    public void stretch(byte[] buf, int n) {
        if (buf == null || n <= 0 || n > buf.length) return;
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[buf[i] & 0xFF]++;
        int lowCount = n / 100;          // 1%
        int highCount = n - n / 100;     // 99%
        int acc = 0;
        int lo = 0;
        int hi = 255;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= lowCount) {
                lo = v;
                break;
            }
        }
        acc = 0;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= highCount) {
                hi = v;
                break;
            }
        }
        if (hi - lo < 12) return;        // 本来就是一整片，拉伸只会把噪声放大
        if (lo == 0 && hi == 255) return; // 已经是满量程

        byte[] lut = new byte[256];
        int span = hi - lo;
        for (int v = 0; v < 256; v++) {
            int x = (v - lo) * 255 / span;
            if (x < 0) x = 0;
            if (x > 255) x = 255;
            lut[v] = (byte) x;
        }
        for (int i = 0; i < n; i++) buf[i] = lut[buf[i] & 0xFF];
    }

    /**
     * 整体亮度平移（原地）。二值化本质上是拿一个阈值去切，把亮度整体挪 30，
     * 等效于把阈值挪 30：模块只有一两个像素、边缘是灰渐变时，同一个码在
     * 不同阈值下切出来的宽窄比不一样，多试几档就等于把亚像素边缘找回来。
     */
    public void shift(byte[] buf, int n, int delta) {
        if (buf == null || delta == 0 || n <= 0) return;
        if (n > buf.length) n = buf.length;
        byte[] lut = new byte[256];
        for (int v = 0; v < 256; v++) {
            int x = v + delta;
            lut[v] = (byte) (x < 0 ? 0 : (x > 255 ? 255 : x));
        }
        for (int i = 0; i < n; i++) buf[i] = lut[buf[i] & 0xFF];
    }

    /**
     * 在整幅图里自动找一维码的位置，写进 out = {left, top, right, bottom}（右/下不含）。
     *
     * 两步走，顺序不能反：
     *  1) **行梯度投影**找上下边界。一维码所在的行水平跳变极强，背景几乎没有，
     *     逐行算 |p[x+2]-p[x]| 均值，取峰值行向上下扩展到掉到 35% 为止。
     *  2) 在已经确定的这条横带**内部**做列梯度找左右边界。单独按列找不稳——
     *     一维码两端是静区，列梯度峰谷平坦，容易锁到中间的宽条上；先把上下
     *     的干扰行排除掉，列梯度才可靠。
     *
     * 固定比例的扫描带只要条码偏出中带就整块落空，实测难读条件下 8% vs 33%，
     * 这就是这一步存在的理由。
     *
     * @return false 表示画面里没有可信的条码区（此时调用方应退回固定比例裁剪）
     */
    public boolean locateBars(byte[] src, int w, int h, int[] out) {
        if (src == null || w < 96 || h < 96 || out == null || out.length < 4) return false;

        /* ---- 1) 行梯度投影 ---- */
        int rstep = 2;
        int rows = h / rstep;
        if (rowE.length != rows) {
            rowE = new int[rows];
            rowS = new int[rows];
        }
        int[] g = rowE;
        int[] sm = rowS;
        int samples = Math.max(1, (w - 2 + 1) / 2);
        for (int r = 0; r < rows; r++) {
            int base = (r * rstep) * w;
            int s = 0;
            for (int x = 0; x + 2 < w; x += 2) {
                int d = (src[base + x + 2] & 0xFF) - (src[base + x] & 0xFF);
                s += d < 0 ? -d : d;
            }
            g[r] = s / samples;
        }
        // 纵向 5 点平滑，压掉单行噪声
        for (int r = 0; r < rows; r++) {
            int a = 0;
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
        int mx = 0;
        int peak = 0;
        for (int r = 0; r < rows; r++) {
            if (sm[r] > mx) {
                mx = sm[r];
                peak = r;
            }
        }
        // 整幅都没有明显竖条纹：画面里没码，或对比度低到无解
        if (mx < 10) return false;

        int th = (int) (mx * 0.35f);
        int t = peak;
        int b = peak;
        while (t > 0 && sm[t - 1] >= th) t--;
        while (b < rows - 1 && sm[b + 1] >= th) b++;

        int top = t * rstep;
        int bot = Math.min(h, b * rstep + rstep);
        if (bot - top < 24) return false;
        // 条码占了大半屏时说明阈值把整幅都圈进来了，收窄到峰值附近
        int cap = (int) (h * 0.72f);
        if (bot - top > cap) {
            int mid = (top + bot) / 2;
            top = Math.max(0, mid - cap / 2);
            bot = Math.min(h, top + cap);
        }

        /* ---- 2) 带内列梯度找左右边界 ---- */
        if (colE.length != w) colE = new int[w];
        Arrays.fill(colE, 0, w, 0);
        int bandRows = 0;
        for (int y = top; y < bot; y += 2) {
            int base = y * w;
            bandRows++;
            for (int x = 1; x < w - 1; x++) {
                int d = (src[base + x + 1] & 0xFF) - (src[base + x - 1] & 0xFF);
                if (d > 26 || d < -26) colE[x]++;
            }
        }
        if (bandRows <= 0) return false;
        // 条码里的每一列要么正好压在条/空边界上（几乎每一行都跳），要么完全没有跳变，
        // 分离度极高，取 35% 足够
        int hit = (int) (bandRows * 0.35f);
        if (hit < 2) hit = 2;

        int bestL = -1;
        int bestR = -1;
        int bestLen = 0;
        int x = 1;
        while (x < w - 1) {
            if (colE[x] < hit) {
                x++;
                continue;
            }
            int l = x;
            int r = x;
            int gap = 0;
            for (int i = x; i < w - 1; i++) {
                if (colE[i] >= hit) {
                    r = i;
                    gap = 0;
                } else if (++gap > 6) {
                    break;
                }
            }
            if (r - l > bestLen) {
                bestLen = r - l;
                bestL = l;
                bestR = r;
            }
            x = r + 1;
        }
        // 太窄的一段不可能装下 233 个模块，多半是纸边或文字
        if (bestLen < 80) return false;

        int padX = Math.max(6, bestLen / 12);
        int padY = Math.max(4, (bot - top) / 10);
        int L = Math.max(0, bestL - padX);
        int R = Math.min(w, bestR + 1 + padX);
        int T = Math.max(0, top - padY);
        int B2 = Math.min(h, bot + padY);
        if (R - L < 60 || B2 - T < 24) return false;

        out[0] = L;
        out[1] = T;
        out[2] = R;
        out[3] = B2;
        return true;
    }

    /**
     * 3x3 十字形轻度锐化（原地）：out = (8c - 左 - 右 - 上 - 下) / 4。
     * 打印分辨率低 + 镜头轻微失焦时，条码边缘是渐变而不是硬边，
     * 锐化把边缘重新拉陡，ZXing 才能数出正确的宽窄比。
     * 只做一遍：做两遍会开始放大噪声，实测反而出现读错。
     */
    public void sharpen(byte[] buf, int w, int h) {
        int n = w * h;
        if (buf == null || n <= 0 || n > buf.length || w < 3 || h < 3) return;
        if (copyBuf.length != n) copyBuf = new byte[n];
        System.arraycopy(buf, 0, copyBuf, 0, n);
        byte[] src = copyBuf;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int up = (y == 0 ? row : row - w);
            int dn = (y == h - 1 ? row : row + w);
            for (int x = 0; x < w; x++) {
                int left = x == 0 ? x : x - 1;
                int right = x == w - 1 ? x : x + 1;
                int c = src[row + x] & 0xFF;
                int s = (src[row + left] & 0xFF) + (src[row + right] & 0xFF)
                        + (src[up + x] & 0xFF) + (src[dn + x] & 0xFF);
                int v = (8 * c - s) >> 2;
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                buf[row + x] = (byte) v;
            }
        }
    }
}
