package com.pankscan.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生扫码层：Camera 取景 + ZXing 解码。
 * 覆盖在 WebView 之上，扫到的文本回调给 MainActivity 再注入网页；
 * 网页判定完（新增/重复）再回调 report()，由这里出对应的提示音和文字。
 */
public class ScanOverlay extends FrameLayout implements SurfaceHolder.Callback {

    public interface Listener {
        void onScan(String text);

        void onStopped();

        void onError(String msg);
    }

    /** report() 的结果码 */
    public static final int RESULT_OK = 1;        // 已记录
    public static final int RESULT_DUP = 0;       // 重复，已跳过
    public static final int RESULT_NO_PALLET = 2; // 还没设栈板
    public static final int RESULT_SILENT = 3;    // 忽略，不响不提示

    private static final String TAG = "PanScan";
    /** 同一内容多久内不重复上报 */
    private static final long SAME_CODE_GAP = 2000L;
    /** 等待网页回传结果的超时；超时按"已记录"处理，保证每次扫码都有反馈 */
    private static final long FEEDBACK_TIMEOUT = 500L;
    /** 同一内容需要连续几帧一致才认定，用来滤掉误读 */
    private static final int CONFIRM_HITS = 2;
    /** 单帧最多花多少毫秒解码，超了就把剩下的尝试留给下一帧 */
    private static final long FRAME_BUDGET_MS = 90L;
    /** 连续多久没读出来就提示用户调整距离/补光 */
    private static final long SLOW_HINT_MS = 9000L;

    /**
     * 中间扫描带的位置（占画面比例），和取景框大致对齐、再留一圈余量。
     * 只在中间这一条里解码，一是让 ZXing 的行扫描集中打在条码上，
     * 二是避开画面边上的桌面、纸边这些干扰。
     * 定位成功时不用这组比例，只有定位失败才退回这里兜底。
     */
    private static final float BAND_L = 0.15f;
    private static final float BAND_T = 0.30f;
    private static final float BAND_R = 0.85f;
    private static final float BAND_B = 0.62f;
    /** 阈值偏置三档，逐帧轮换（实测 ±0.12 最好，再多反而开始读错） */
    private static final float[] BAND_BIAS = {0f, -0.12f, 0.12f};

    /**
     * 取景框宽度占屏宽的比例。
     * 框画得越大，用户越会往后退着把整张标签框进来——而这类标签贴到 25cm 就基本读不出。
     * 缩到 0.68 等于用框去「逼」用户靠近。
     */
    private static final float BOX_W = 0.68f;
    private static final float BOX_RATIO = 0.46f;

    /** 变焦档位（百分比，100 = 不放大）。数字放大到 300 以上只会更糊，不会更清楚 */
    private static final int[] ZOOM_TARGET = {100, 200, 300};

    private static final String HINT_IDLE = "条码放进框内，近一点更清楚";
    private static final String HINT_SLOW = "还读不出？走近一点，或点 2× 放大";

    private Listener listener;

    private PreviewSurface surface;
    private MaskView mask;
    private TextView hint;
    private TextView counter;
    private TextView status;
    private TextView errText;
    private Button torchBtn;

    private Camera camera;
    private int cameraId = -1;
    private Camera.Size previewSize;
    private int rotationDeg = 90;

    private MultiFormatReader reader;
    private MultiFormatReader readerHard;
    private MultiFormatReader readerFast;
    private final ImageOps ops = new ImageOps();
    private byte[] frameBuf = new byte[0];
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private volatile boolean running;
    private long frameSeq;
    private boolean wantRunning;
    private boolean qrEnabled;
    private boolean torchOn;
    private boolean vibeEnabled = true;
    /** 相机支持的数字变焦倍率表（百分数），不支持时为 null */
    private List<Integer> zoomRatios;
    /** 当前变焦档位，对应 ZOOM_TARGET 的下标 */
    private int zoomStep;
    private Button zoomBtn;
    /** 网页同步过来的「本次已扫条数」，只用于结束扫描时的确认文案 */
    private int scannedCount;
    /** 结束扫描的确认框是否已弹出，防止连按返回键叠出两个框 */
    private boolean confirmShown;
    /** locateBars 的输出 {l,t,r,b}，复用避免每帧分配 */
    private final int[] locBox = new int[4];
    /** 本机识别画像：记住这台手机哪种扫法最灵，并据此调整解码顺序 */
    private final ScanProfile profile;

    private String lastText = "";
    private long lastTs = 0;
    private String pendingText = "";
    private int pendingHits = 0;
    private long pendingTs = 0;
    private String lastFormat = "";

    private String awaitingText = "";
    private boolean awaiting;

    private final TonePlayer tone = new TonePlayer();
    private Vibrator vibrator;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public ScanOverlay(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        setVisibility(GONE);
        profile = new ScanProfile(ctx);

        surface = new PreviewSurface(ctx);
        LayoutParams sp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sp.gravity = Gravity.CENTER;
        addView(surface, sp);
        surface.getHolder().addCallback(this);

        mask = new MaskView(ctx);
        addView(mask, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(ctx);
        hint.setText(HINT_IDLE);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        LayoutParams hp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(56);
        addView(hint, hp);

        counter = new TextView(ctx);
        counter.setText("已扫 0 条");
        counter.setTextColor(0xFFFFFFFF);
        counter.setTextSize(15);
        counter.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        LayoutParams cp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.TOP | Gravity.RIGHT;
        cp.topMargin = dp(56);
        cp.rightMargin = dp(18);
        addView(counter, cp);

        // 扫框下方的结果提示：已记录 / 重复 / 请先设栈板
        status = new TextView(ctx);
        status.setText("对准条码，可连续扫描");
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(0f, 1.15f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB8000000);
        bg.setCornerRadius(dp(12));
        status.setBackground(bg);
        status.setPadding(dp(18), dp(10), dp(18), dp(10));
        addView(status, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        errText = new TextView(ctx);
        errText.setTextColor(0xFFFF9EA0);
        errText.setTextSize(13);
        errText.setVisibility(GONE);
        LayoutParams ep = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ep.topMargin = dp(86);
        addView(errText, ep);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        zoomBtn = new Button(ctx);
        zoomBtn.setText("1×");
        zoomBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleZoom();
            }
        });
        torchBtn = new Button(ctx);
        torchBtn.setText("手电筒");
        torchBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTorch();
            }
        });
        Button close = new Button(ctx);
        close.setText("结束扫描");
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                requestStop();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(8);
        bp.rightMargin = dp(8);
        bar.addView(zoomBtn, bp);
        bar.addView(torchBtn, bp);
        bar.addView(close, bp);

        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(48);
        addView(bar, lp);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 供网页侧同步「声音 / 震动」开关 */
    public void setFeedback(boolean sound, boolean vibe) {
        tone.setEnabled(sound);
        vibeEnabled = vibe;
    }

    /* ---------------- 对外控制 ---------------- */

    public void begin(boolean qr) {
        qrEnabled = qr;
        buildReader();
        setCount(0);
        hideError();
        setStatusNeutral();
        setVisibility(VISIBLE);
        armSlowHint();
        wantRunning = true;
        SurfaceHolder h = surface.getHolder();
        if (h.getSurface() != null && h.getSurface().isValid()) {
            openCamera();
        }
    }

    public void stop() {
        releaseCamera();
        wantRunning = false;
        running = false;
        setVisibility(GONE);
        if (listener != null) listener.onStopped();
    }

    /** 扫码层是否正开着（返回键据此决定是「结束扫描」还是「退出程序」） */
    public boolean isActive() {
        return wantRunning && getVisibility() == VISIBLE;
    }

    /**
     * 请求结束扫描。
     *
     * 扫描界面铺满全屏、底部按钮就在拇指下面，一手拿标签一手拿手机时很容易碰到。
     * 已经扫到的数据其实存在网页的 localStorage 里、掉了也还在，但用户并不知道这一点，
     * 误触退出会让人以为白扫了一轮。所以只要本次有收获就多问一句；
     * 一条没扫到说明是刚点开就误入，直接退出，不拿废话挡路。
     */
    public void requestStop() {
        if (scannedCount <= 0) {
            stop();
            return;
        }
        if (confirmShown) return;
        // 页面正在收尾时弹框会炸（WindowLeaked / BadToken），这种情况直接收工
        if (getWindowToken() == null) {
            stop();
            return;
        }
        confirmShown = true;
        new AlertDialog.Builder(getContext())
                .setTitle("结束扫描？")
                .setMessage("当前栈板已有 " + scannedCount + " 条，都已经记下来了，不会丢。")
                .setNegativeButton("继续扫", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        confirmShown = false;
                    }
                })
                .setPositiveButton("结束", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        confirmShown = false;
                        stop();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        confirmShown = false;
                    }
                })
                .show();
    }

    /** Activity 回到前台时，如果扫码层还开着就把相机重新打开 */
    public void resume() {
        if (!wantRunning || getVisibility() != VISIBLE) return;
        SurfaceHolder h = surface.getHolder();
        if (h.getSurface() != null && h.getSurface().isValid()) {
            openCamera();
        }
    }

    public void setCount(int n) {
        scannedCount = n;
        counter.setText("已扫 " + n + " 条");
    }

    /** Activity 退到后台时调用，必须释放相机，否则会占住摄像头导致其他应用打不开 */
    public void releaseCamera() {
        running = false;
        awaiting = false;
        ui.removeCallbacks(feedbackTimeout);
        ui.removeCallbacks(slowHint);
        if (profile != null) profile.flush();
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Exception ignore) {
            }
            try {
                camera.release();
            } catch (Exception ignore) {
            }
            camera = null;
        }
    }

    /** 网页判定完成后回传结果，决定提示音和扫框下方的文字 */
    public void report(final int code, final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                awaiting = false;
                ui.removeCallbacks(feedbackTimeout);
                applyResult(code, text);
            }
        });
    }

    /* ---------------- 相机 ---------------- */

    private void openCamera() {
        if (camera != null) return;
        try {
            cameraId = findBackCamera();
            camera = Camera.open(cameraId);
        } catch (Throwable e) {
            camera = null;
            showError("相机打开失败：" + e.getMessage());
            if (listener != null) listener.onError("相机打开失败：" + e.getMessage());
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = chooseSize(p.getSupportedPreviewSizes());
            if (best != null) p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);
            String focus = chooseFocus(p.getSupportedFocusModes());
            if (focus != null) p.setFocusMode(focus);
            camera.setParameters(p);

            Camera.Parameters after = camera.getParameters();
            previewSize = after.getPreviewSize();
            if (previewSize == null) previewSize = best;
        } catch (Throwable e) {
            Log.w(TAG, "setParameters failed", e);
            Camera.Parameters after = camera.getParameters();
            previewSize = after.getPreviewSize();
        }

        if (previewSize != null) {
            surface.setAspect((float) previewSize.height / (float) previewSize.width);
            int need = previewSize.width * previewSize.height;
            if (frameBuf.length != need) frameBuf = new byte[need];
            profile.setPreview(previewSize.width + "×" + previewSize.height);
        }

        // 读一次变焦倍率表：现场标签一个模块只有 0.1mm 量级，靠走近解决最省事，
        // 但手伸不过去的时候，数字变焦是唯一能在不损失细节的前提下把画面「拉近」的手段
        try {
            Camera.Parameters zp = camera.getParameters();
            zoomRatios = zp.isZoomSupported() ? zp.getZoomRatios() : null;
        } catch (Throwable e) {
            zoomRatios = null;
        }
        if (zoomRatios == null || zoomRatios.isEmpty()) {
            zoomBtn.setEnabled(false);
            zoomBtn.setText("固定");
        }

        try {
            rotationDeg = computeRotation(cameraId);
            camera.setDisplayOrientation(rotationDeg);
            camera.setPreviewDisplay(surface.getHolder());
            camera.startPreview();
            running = true;
        } catch (Throwable e) {
            showError("预览启动失败：" + e.getMessage());
            if (listener != null) listener.onError("预览启动失败：" + e.getMessage());
            releaseCamera();
            return;
        }
        applyZoom();

        if (!afterAutoFocusSupported()) startContinuousFocus();
        camera.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera cam) {
                if (!running || busy || data == null || previewSize == null) return;
                final int w = previewSize.width;
                final int h = previewSize.height;
                if (frameBuf.length != w * h) return;
                busy = true;
                try {
                    // 必须在这个回调线程里立刻拷走：预览缓冲会被下一帧覆盖，
                    // 丢给工作线程再拷会读到撕裂的画面，解出来就是乱七八糟的内容。
                    int stride = strideOf(data.length, w, h);
                    if (stride == w) {
                        System.arraycopy(data, 0, frameBuf, 0, w * h);
                    } else {
                        for (int row = 0; row < h; row++) {
                            System.arraycopy(data, row * stride, frameBuf, row * w, w);
                        }
                    }
                } catch (Throwable t) {
                    busy = false;
                    return;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handleFrame(frameBuf, w, h);
                        } catch (Throwable t) {
                            Log.w(TAG, "decode error", t);
                        } finally {
                            busy = false;
                        }
                    }
                });
            }
        });
    }

    /**
     * NV21 的 Y 平面可能带行填充（stride > width）。若仍按 width 当跨度读，
     * 整幅图会沿对角线错位，条码就永远读不对。
     */
    private int strideOf(int len, int w, int h) {
        int expect = w * h * 3 / 2;
        if (len == expect) return w;
        int s = (int) Math.round((double) len * 2.0 / (3.0 * (double) h));
        if (s > w && s <= w + 4096) return s;
        return w;
    }

    private boolean afterAutoFocusSupported() {
        try {
            String m = camera.getParameters().getFocusMode();
            return Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(m)
                    || Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO.equals(m);
        } catch (Exception e) {
            return false;
        }
    }

    private final Runnable afTask = new Runnable() {
        @Override
        public void run() {
            if (!running || camera == null) return;
            try {
                camera.autoFocus(null);
            } catch (Exception ignore) {
            }
            ui.postDelayed(this, 1800);
        }
    };

    private void startContinuousFocus() {
        ui.postDelayed(afTask, 1200);
    }

    private int findBackCamera() {
        int n = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < n; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    /**
     * 选预览分辨率：**按短边最大挑**，不是按面积最大挑。
     *
     * 竖屏取景时，条码的宽度方向落在画幅的短边上，所以决定「一个模块占几个像素」的
     * 是短边，面积不重要。旧逻辑按面积排、上限 1920×1080，会把 1600×1200
     * （短边 1200、面积 1.92M）判给 1920×1080（短边 1080、面积 2.07M），
     * 白丢 11% 的横向像素；换成 1920×1440 更是直接多 33%。
     *
     * 上限放到 3.1M：4K 预览能拿到的像素并不比 3M 多多少，解码耗时要翻倍，
     * 而且很多机型 4K 预览会掉帧。
     */
    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        int bestShort = -1;
        long bestArea = -1;
        for (Camera.Size s : sizes) {
            int longSide = Math.max(s.width, s.height);
            int shortSide = Math.min(s.width, s.height);
            if (longSide <= 0 || shortSide <= 0) continue;
            long area = (long) s.width * s.height;
            if (area > 3_100_000L) continue;
            if (shortSide < 480) continue;
            double ratio = (double) longSide / (double) shortSide;
            boolean okRatio = Math.abs(ratio - 16.0 / 9.0) < 0.08
                    || Math.abs(ratio - 4.0 / 3.0) < 0.08;
            if (!okRatio) continue;
            if (shortSide > bestShort || (shortSide == bestShort && area > bestArea)) {
                bestShort = shortSide;
                bestArea = area;
                best = s;
            }
        }
        if (best != null) return best;

        // 没有理想比例时退回最接近 1280x720 的
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long score = Math.abs((long) s.width * s.height - 1280L * 720L);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private String chooseFocus(List<String> modes) {
        if (modes == null) return null;
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            return Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
        }
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            return Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
        }
        if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            return Camera.Parameters.FOCUS_MODE_AUTO;
        }
        return null;
    }

    private int computeRotation(int id) {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            int r = wm.getDefaultDisplay().getRotation();
            int deg = 0;
            if (r == Surface.ROTATION_90) deg = 90;
            else if (r == Surface.ROTATION_180) deg = 180;
            else if (r == Surface.ROTATION_270) deg = 270;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return (360 - ((info.orientation + deg) % 360)) % 360;
            }
            return (info.orientation - deg + 360) % 360;
        } catch (Exception e) {
            return 90;
        }
    }

    /** 在 1× / 2× / 3× 之间轮换 */
    private void cycleZoom() {
        if (zoomRatios == null || zoomRatios.isEmpty()) {
            showError("此机型不支持变焦");
            return;
        }
        zoomStep = (zoomStep + 1) % ZOOM_TARGET.length;
        applyZoom();
    }

    /**
     * 应用当前变焦档位。
     *
     * 这里的数字变焦是**真变焦**，不是把画面位图放大：预览流本来就是传感器几千万像素
     * 降采样来的，裁中间一块再送到预览尺寸，同一个模块落到的采样点反而更多。
     * 实测现场标签在 2× 下能读出来、1× 读不出，就是这个道理。
     */
    private void applyZoom() {
        if (camera == null || zoomRatios == null || zoomRatios.isEmpty()) return;
        try {
            int target = ZOOM_TARGET[zoomStep % ZOOM_TARGET.length];
            int idx = 0;
            int n = zoomRatios.size();
            for (int i = 0; i < n; i++) {
                if (zoomRatios.get(i) <= target + 8) idx = i;
                else break;
            }
            Camera.Parameters p = camera.getParameters();
            int cur = -1;
            try {
                cur = p.getZoom();
            } catch (Throwable ignore) {
            }
            if (cur != idx) {
                p.setZoom(idx);
                camera.setParameters(p);
            }
            if (zoomBtn != null) {
                int real = zoomRatios.get(idx);
                String label = real % 100 == 0
                        ? (real / 100) + "×"
                        : String.format(java.util.Locale.US, "%.1f×", real / 100f);
                zoomBtn.setText(label);
                profile.setZoom(label);
            }
        } catch (Throwable e) {
            showError("变焦不可用");
        }
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            torchOn = !torchOn;
            p.setFlashMode(torchOn ? Camera.Parameters.FLASH_MODE_TORCH
                    : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            torchBtn.setText(torchOn ? "关闭手电" : "手电筒");
        } catch (Exception e) {
            torchOn = false;
            showError("手电筒不可用");
        }
    }

    /* ---------------- 解码 ---------------- */

    private void buildReader() {
        reader = newReader(false, false);
        readerHard = newReader(true, false);
        // 只认 Code 128 的快速通路：多格式 reader 在解不出来时要挨个跑完 8 种码制，
        // 单帧花的时间是这里的七八倍。现场绝大多数码是 Code 128，先让它几毫秒出结果。
        readerFast = newReader(false, true);
    }

    private MultiFormatReader newReader(boolean hard, boolean code128Only) {
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        List<BarcodeFormat> fmts = new ArrayList<BarcodeFormat>();
        fmts.add(BarcodeFormat.CODE_128);
        if (!code128Only) {
            fmts.add(BarcodeFormat.CODE_39);
            fmts.add(BarcodeFormat.CODE_93);
            fmts.add(BarcodeFormat.EAN_13);
            fmts.add(BarcodeFormat.EAN_8);
            fmts.add(BarcodeFormat.ITF);
            fmts.add(BarcodeFormat.UPC_A);
            fmts.add(BarcodeFormat.UPC_E);
            // 不用 CODABAR：它没有校验位，画面里的杂纹、印刷文字很容易被误判成码，
            // 表现就是"一直在读，读出来的内容每次都不一样"。
            if (qrEnabled) {
                fmts.add(BarcodeFormat.QR_CODE);
                fmts.add(BarcodeFormat.DATA_MATRIX);
            }
        }
        hints.put(DecodeHintType.POSSIBLE_FORMATS, fmts);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(DecodeHintType.TRY_HARDER, hard ? Boolean.TRUE : Boolean.FALSE);
        MultiFormatReader r = new MultiFormatReader();
        r.setHints(hints);
        return r;
    }

    /**
     * 取景帧的方向和手机摆放方向不一定一致：一维码只有在画面里「竖着排」时才解得出来。
     * 所以按「代价从低到高」依次尝试，哪一步成功就以哪一步为准。
     *
     * 顺序是按实测定下来的：现场条码打印分辨率低，全幅直接解大概率读不出来，
     * 真正管用的是第 2 步的中间扫描带（裁剪 + 锐化 + 阈值偏置，
     * 自测里识别率从 39% 提到 80%）。把它排在多格式全幅前面，避免每帧先白跑一遍最贵的解码。
     */
    private void handleFrame(byte[] y, int w, int h) {
        if (reader == null) return;
        long t0 = System.currentTimeMillis();
        long seq = ++frameSeq;
        // 正常竖持是 90 度（顺时针转正），270 度反过来。个别设备（或横屏时）
        // 相机朝向本身就是 0/180，这时缓冲已经是正的，不能再转。
        boolean noRot = rotationDeg == 0 || rotationDeg == 180;
        boolean cw = rotationDeg != 270;

        byte[] up = noRot ? y : ops.rotate(y, w, h, cw, 0);
        int uw = noRot ? w : h;
        int uh = noRot ? h : w;

        // 1) 全幅 + 只认 Code 128：清楚的大码几毫秒就出结果，现场九成标签走这条
        Result r = decode(up, uw, uh, readerFast, ImageOps.BIN_HYBRID);
        if (r != null) {
            hit(0, t0);
            verifyAndEmit(r);
            return;
        }

        // 2) 之后两段的先后顺序交给本机画像：哪段在这台手机上更常出结果，就先走哪段。
        //    证据不足时（前 24 次命中之前）保持原顺序，不会因为个别样本乱跳。
        int biasIdx = profile.pickBias(seq);
        boolean locFirst = profile.preferLocated();

        if (locFirst) {
            r = tryLocated(up, uw, uh, biasIdx, t0);
            if (r != null) {
                verifyAndEmit(r);
                return;
            }
        }

        // 3) 中间扫描带：低分辨率、弱对比、边缘发虚的条码主要靠这条。
        //    排在多格式全幅前面，是因为全幅要挨个跑完 8 种码制，最费时间，
        //    而难读的标签恰恰每次都要走到底。
        r = decodeBand(up, uw, uh, biasIdx);
        if (r != null) {
            hit(1, t0);
            profile.hitBias(biasIdx % 3);
            verifyAndEmit(r);
            return;
        }

        // 3b) 自动定位：条码不在画面中带时，固定带整块落空，这一步把框自己找出来。
        if (!locFirst) {
            r = tryLocated(up, uw, uh, biasIdx, t0);
            if (r != null) {
                verifyAndEmit(r);
                return;
            }
        }

        // 4) 全幅 + 全格式：Code 39 / EAN / ITF 这些走这条
        if (System.currentTimeMillis() - t0 < FRAME_BUDGET_MS) {
            r = decode(up, uw, uh, reader, ImageOps.BIN_HYBRID);
            if (r != null) {
                hit(3, t0);
                verifyAndEmit(r);
                return;
            }
        }

        // 5) 全幅 TRY_HARDER：条码没落在中间扫描带里时兜底
        if (System.currentTimeMillis() - t0 < FRAME_BUDGET_MS) {
            r = decode(up, uw, uh, readerHard, ImageOps.BIN_HYBRID);
            if (r != null) {
                hit(3, t0);
                verifyAndEmit(r);
                return;
            }
        }

        // 5) 反方向只在还没结果时隔几帧试一次（QR / DataMatrix 才真正需要它）
        if (!noRot && (seq & 3) == 0 && System.currentTimeMillis() - t0 < FRAME_BUDGET_MS) {
            byte[] op = ops.rotate(y, w, h, !cw, 1);
            r = decode(op, uw, uh, readerHard, ImageOps.BIN_HYBRID);
            if (r != null) verifyAndEmit(r);
        }
    }

    /**
     * 中间扫描带解码。
     *
     * 三步都是针对「打印分辨率不高」这个具体问题：
     *  - 裁剪：只保留取景框附近，行扫描全部打在条码上，也不会被桌面纹理带偏；
     *  - 锐化：打印点阵 + 镜头微失焦会让条码边缘变成灰渐变，锐化把边缘拉陡；
     *  - 阈值偏置：模块只有一两个像素时，边缘落在像素中间，换个阈值切出来的
     *    宽窄比就不一样，逐帧轮换三档等于把亚像素边缘找回来。
     *
     * 二值化同时试局部自适应和全局直方图：前者抗光照不均，后者对低对比度更灵。
     */
    private Result decodeBand(byte[] up, int uw, int uh, int biasIdx) {
        byte[] band = ops.cropUpscale(up, uw, uh, BAND_L, BAND_T, BAND_R, BAND_B, 1);
        if (band == null) return null;
        return enhanceDecode(band, ops.outWidth(), ops.outHeight(), biasIdx);
    }

    /** 裁「自动定位到的条码框」再走同一套增强 */
    private Result decodeBox(byte[] up, int uw, int uh, int biasIdx) {
        byte[] box = ops.cropRect(up, uw, uh, locBox[0], locBox[1], locBox[2], locBox[3], 1);
        if (box == null) return null;
        return enhanceDecode(box, ops.outWidth(), ops.outHeight(), biasIdx);
    }

    /** 定位 + 解码，成功时顺手记一笔画像；失败返回 null 让调用方继续往下走 */
    private Result tryLocated(byte[] up, int uw, int uh, int biasIdx, long t0) {
        if (System.currentTimeMillis() - t0 >= FRAME_BUDGET_MS) return null;
        if (!ops.locateBars(up, uw, uh, locBox)) return null;
        int b = (biasIdx + 1) % BAND_BIAS.length;
        Result r = decodeBox(up, uw, uh, b);
        if (r != null) {
            hit(2, t0);
            profile.hitBias(b);
        }
        return r;
    }

    /** 记一次「这一段出结果了」，连同耗时一起进本机画像 */
    private void hit(int kind, long t0) {
        profile.hit(kind);
        profile.tickMs((int) (System.currentTimeMillis() - t0));
    }

    /** 锐化 + 阈值偏置 + 两种二值化，是「低分辨率标签」这套链路的公共收尾 */
    private Result enhanceDecode(byte[] buf, int bw, int bh, int biasIdx) {
        if (buf == null || bw < 40 || bh < 20) return null;
        ops.sharpen(buf, bw, bh);
        int shift = (int) (BAND_BIAS[biasIdx % BAND_BIAS.length] * 255f);
        if (shift != 0) ops.shift(buf, bw * bh, shift);
        Result r = decode(buf, bw, bh, readerHard, ImageOps.BIN_HYBRID);
        if (r == null) r = decode(buf, bw, bh, readerHard, ImageOps.BIN_GLOBAL);
        return r;
    }

    private Result decode(byte[] buf, int bw, int bh, MultiFormatReader r, int bin) {
        if (r == null || buf == null || bw < 20 || bh < 10) return null;
        try {
            LuminanceSource src = new PlanarYUVLuminanceSource(buf, bw, bh, 0, 0, bw, bh, false);
            Binarizer bz = bin == ImageOps.BIN_GLOBAL
                    ? new GlobalHistogramBinarizer(src) : new HybridBinarizer(src);
            return r.decodeWithState(new BinaryBitmap(bz));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 同一内容要连续两帧都读到才认：单帧误读（尤其是不带校验位的码制）过不了这一关，
     * 这是"读出来的内容每次都不一样"的最后一道闸。
     */
    private void verifyAndEmit(Result r) {
        String text = r.getText() == null ? "" : r.getText().trim();
        if (text.length() == 0) return;
        BarcodeFormat fmt = r.getBarcodeFormat();

        // 误读几乎都来自「Code 128 的条纹被 EAN/ITF 解码器抢答」：
        // 它们只有一位校验，比 Code 128 的两个校验符松得多。所以别的码制必须
        // 长得像现场 SN 才认；Code 128 本身的校验够强，一律放行。
        if (fmt != BarcodeFormat.CODE_128 && isOneD(fmt) && !profile.snOk(text)) return;

        if (fmt != null) lastFormat = fmt.name();

        long now = System.currentTimeMillis();
        if (text.equals(pendingText) && now - pendingTs < 1500) {
            pendingHits++;
        } else {
            pendingText = text;
            pendingHits = 1;
        }
        pendingTs = now;
        if (pendingHits < CONFIRM_HITS) return;

        pendingText = "";
        pendingHits = 0;
        emit(text);
    }

    /** 会跟 Code 128 抢条纹的那几种一维码 */
    private static boolean isOneD(BarcodeFormat f) {
        return f == BarcodeFormat.CODE_39 || f == BarcodeFormat.CODE_93
                || f == BarcodeFormat.EAN_13 || f == BarcodeFormat.EAN_8
                || f == BarcodeFormat.UPC_A || f == BarcodeFormat.UPC_E
                || f == BarcodeFormat.ITF;
    }

    /**
     * 现场 SN 白名单：默认 `LXT` + 6 位数字 + `AC` + 7 位数字 = 18 位。
     * 规则本体在 ScanProfile 里——它会把网页侧从已入账记录中学到的前缀/长度并进来，
     * 但只放宽不收紧，绝不会把原本能认的码挡在外面。
     */
    ScanProfile profile() {
        return profile;
    }

    void resetProfile() {
        profile.reset();
    }

    private void emit(final String text) {
        if (text == null) return;
        final String t = text.trim();
        if (t.length() == 0) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (t.equals(lastText) && now - lastTs < SAME_CODE_GAP) return;
            lastText = t;
            lastTs = now;
        }
        awaitingText = t;
        awaiting = true;
        ui.removeCallbacks(feedbackTimeout);
        ui.postDelayed(feedbackTimeout, FEEDBACK_TIMEOUT);
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onScan(t);
                armSlowHint();
            }
        });
    }

    /** 网页迟迟不回结果时按"已记录"收尾，保证每次扫码都有声音 */
    private final Runnable feedbackTimeout = new Runnable() {
        @Override
        public void run() {
            if (!awaiting) return;
            awaiting = false;
            applyResult(RESULT_OK, awaitingText);
        }
    };

    private final Runnable clearStatus = new Runnable() {
        @Override
        public void run() {
            setStatusNeutral();
        }
    };

    /** 一直读不出来时把顶部提示换成"靠近一点"，比干等强 */
    private final Runnable slowHint = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            hint.setText(HINT_SLOW);
            hint.setTextColor(0xFFFFD166);
        }
    };

    private void armSlowHint() {
        ui.removeCallbacks(slowHint);
        hint.setText(HINT_IDLE);
        hint.setTextColor(0xFFFFFFFF);
        ui.postDelayed(slowHint, SLOW_HINT_MS);
    }

    private void setStatusNeutral() {
        status.setText("对准条码，可连续扫描");
        status.setTextColor(0xCCFFFFFF);
        status.setVisibility(VISIBLE);
    }

    private void applyResult(int code, String text) {
        String t = text == null ? "" : text;
        ui.removeCallbacks(clearStatus);
        switch (code) {
            case RESULT_OK:
                status.setText("已记录" + (lastFormat.length() > 0 ? "  ·  " + lastFormat : "")
                        + "\n" + t);
                status.setTextColor(0xFF4ADE80);
                tone.success();
                vibrateOnce();
                break;
            case RESULT_DUP:
                status.setText("重复，已跳过\n" + t);
                status.setTextColor(0xFFFFB020);
                tone.duplicate();
                vibrateTriple();
                break;
            case RESULT_NO_PALLET:
                status.setText("请先设置栈板编码");
                status.setTextColor(0xFFFF6B6B);
                tone.duplicate();
                vibrateTriple();
                break;
            case RESULT_SILENT:
            default:
                return;
        }
        status.setVisibility(VISIBLE);
        ui.postDelayed(clearStatus, 1800);
    }

    private void vibrateOnce() {
        if (!vibeEnabled) return;
        try {
            Vibrator v = vibrator();
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(38, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(38);
            }
        } catch (Throwable ignore) {
        }
    }

    private void vibrateTriple() {
        if (!vibeEnabled) return;
        try {
            Vibrator v = vibrator();
            if (v == null) return;
            long[] p = {0, 70, 60, 70, 60, 70};
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(p, -1));
            } else {
                v.vibrate(p, -1);
            }
        } catch (Throwable ignore) {
        }
    }

    private Vibrator vibrator() {
        if (vibrator == null) {
            vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
        return vibrator;
    }

    /* ---------------- 小工具 ---------------- */

    private void showError(String msg) {
        errText.setText(msg);
        errText.setVisibility(VISIBLE);
    }

    private void hideError() {
        errText.setVisibility(GONE);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // 结果提示要紧贴扫框下沿，得按扫框实际位置摆，所以放在布局完成后
        if (status != null) {
            int w = getWidth();
            int h = getHeight();
            float bw = w * BOX_W;
            float bh = bw * BOX_RATIO;
            float boxTop = h / 2f - bh / 2f - h * 0.04f;
            int boxBottom = (int) (boxTop + bh);
            int sw = status.getMeasuredWidth();
            int sh = status.getMeasuredHeight();
            int sx = (w - sw) / 2;
            int sy = boxBottom + dp(20);
            if (sy + sh > h - dp(110)) sy = h - dp(110) - sh;
            status.layout(sx, sy, sx + sw, sy + sh);
        }
    }

    /* ---------------- SurfaceHolder.Callback ---------------- */

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (wantRunning) openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    /* ---------------- 视图 ---------------- */

    /** 按预览宽高比自动留边的 SurfaceView，避免画面被拉伸 */
    private static class PreviewSurface extends SurfaceView {
        private float aspect = 0f;

        PreviewSurface(Context c) {
            super(c);
        }

        void setAspect(float a) {
            aspect = a;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getSize(heightSpec);
            if (aspect > 0 && w > 0 && h > 0) {
                float cur = (float) w / (float) h;
                if (cur > aspect) {
                    w = (int) (h * aspect);
                } else {
                    h = (int) (w / aspect);
                }
            }
            setMeasuredDimension(w, h);
        }
    }

    /** 遮罩 + 扫码框 + 扫描线 */
    private static class MaskView extends View {
        private final Paint dimPaint = new Paint();
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint laserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float phase = 0f;

        MaskView(Context c) {
            super(c);
            dimPaint.setColor(0x99000000);
            framePaint.setColor(0xE6FFFFFF);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(c.getResources().getDisplayMetrics().density * 2f);
            laserPaint.setColor(0xFFFF5A5A);
            laserPaint.setStrokeWidth(c.getResources().getDisplayMetrics().density * 2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float bw = w * BOX_W;
            float bh = bw * BOX_RATIO;
            float left = (w - bw) / 2f;
            float top = h / 2f - bh / 2f - h * 0.04f;
            box.set(left, top, left + bw, top + bh);

            canvas.drawRect(0, 0, w, box.top, dimPaint);
            canvas.drawRect(0, box.bottom, w, h, dimPaint);
            canvas.drawRect(0, box.top, box.left, box.bottom, dimPaint);
            canvas.drawRect(box.right, box.top, w, box.bottom, dimPaint);

            float r = 12f;
            canvas.drawRoundRect(box, r, r, framePaint);

            float y = box.top + box.height() * phase;
            canvas.drawLine(box.left + 8, y, box.right - 8, y, laserPaint);

            phase += 0.018f;
            if (phase > 1f) phase = 0f;
            postInvalidateDelayed(40);
        }
    }
}
