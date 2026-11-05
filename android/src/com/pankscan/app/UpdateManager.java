package com.pankscan.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 自动更新：读 update.json → 比对 versionCode → 下载 → 交给系统安装器。
 *
 * 为什么不用系统自带的更新通道：这个 App 不走应用商店，只能自己搬。
 * 清单放在仓库根目录的 update.json（raw 地址），APK 放 GitHub Release，
 * 两者都由 publish.sh 一次性写好，不需要服务器。
 *
 * 几个必须交代的边界：
 *  - 只认 HTTPS。清单地址或 APK 地址是 http 一律拒绝。
 *  - versionCode 必须比本机大才提示，不会因为清单写错而降级。
 *  - 下载后按清单里的 sha256 校验；对不上就删掉，绝不把半个包装进系统。
 *  - Android 8 以上要先允许「安装未知应用」，没允许就先跳去设置页，不硬装。
 *  - 网络差就报错退出，不重试、不后台偷跑。
 */
class UpdateManager {

    interface JsBridge {
        void call(String js);
    }

    private static final String APK_TYPE = "application/vnd.android.package-archive";
    private static final int CONNECT_TIMEOUT = 9000;
    private static final int READ_TIMEOUT = 12000;

    private final Activity act;
    private final JsBridge js;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean busy;
    private String apkUrl = "";
    private String apkSha = "";

    UpdateManager(Activity a, JsBridge b) {
        this.act = a;
        this.js = b;
    }

    /* ---------------- 对外 ---------------- */

    void check() {
        if (busy) {
            emitInfo("{\"error\":\"正在检查中，请稍候\"}");
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = httpGet(AppConfig.MANIFEST_URL, 64 * 1024);
                    JSONObject o = new JSONObject(body);
                    int remoteCode = o.optInt("versionCode", 0);
                    String remoteName = o.optString("versionName", "");
                    apkUrl = o.optString("apk", "");
                    apkSha = o.optString("sha256", "");
                    if (apkUrl.length() > 0 && !apkUrl.startsWith("https://")) {
                        throw new Exception("安装包地址不是 HTTPS");
                    }
                    int cur = currentVersionCode();
                    JSONObject out = new JSONObject();
                    out.put("hasUpdate", remoteCode > cur);
                    out.put("versionName", remoteName);
                    out.put("notes", o.optString("notes", ""));
                    out.put("size", o.optString("size", ""));
                    out.put("currentCode", cur);
                    out.put("remoteCode", remoteCode);
                    emitInfo(out.toString());
                } catch (Throwable t) {
                    emitInfo("{\"error\":" + JSONObject.quote(readable(t)) + "}");
                } finally {
                    busy = false;
                }
            }
        }, "update-check").start();
    }

    void downloadAndInstall() {
        if (apkUrl.length() == 0) {
            emitDone("没有可下载的安装包，请先检查更新");
            return;
        }
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File out = null;
                try {
                    File dir = new File(act.getCacheDir(), "share");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法写入缓存目录");
                    // 先清掉上一次的残包，避免拿半个文件去校验
                    File[] olds = dir.listFiles();
                    if (olds != null) {
                        for (File f : olds) {
                            if (f.getName().toLowerCase().endsWith(".apk")) f.delete();
                        }
                    }
                    String name = "pankscan_update.apk";
                    out = new File(dir, name);

                    HttpURLConnection c = open(apkUrl);
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[32 * 1024];
                    long got = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != lastPct && pct % 4 == 0) {
                                lastPct = pct;
                                emitProgress(pct);
                            }
                        }
                    }
                    fo.flush();
                    fo.close();
                    in.close();
                    c.disconnect();

                    if (apkSha.length() > 0) {
                        String got2 = sha256(out);
                        if (!apkSha.equalsIgnoreCase(got2)) {
                            out.delete();
                            throw new Exception("安装包校验值不符，已删除");
                        }
                    }
                    install(out);
                } catch (Throwable t) {
                    if (out != null) out.delete();
                    emitDone("下载失败：" + readable(t));
                } finally {
                    busy = false;
                }
            }
        }, "update-download").start();
    }

    /* ---------------- 安装 ---------------- */

    private void install(File f) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                s.setData(Uri.parse("package:" + act.getPackageName()));
                act.startActivity(s);
                emitDone("请在设置里允许「安装未知应用」，再点一次「下载并安装」");
                return;
            }
            Uri uri = Uri.parse("content://com.pankscan.app.files/" + Uri.encode(f.getName()));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, APK_TYPE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            emitDone("安装包已就绪，请按系统提示完成安装");
        } catch (Throwable t) {
            emitDone("打不开安装程序：" + t.getMessage());
        }
    }

    /* ---------------- 工具 ---------------- */

    private int currentVersionCode() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private String httpGet(String url, int max) throws Exception {
        HttpURLConnection c = open(url);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) break;
        }
        in.close();
        c.disconnect();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private HttpURLConnection open(String url) throws Exception {
        if (!url.startsWith("https://")) throw new Exception("只支持 HTTPS");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "PanScan/" + currentVersionCode());
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        return c;
    }

    private String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    private static String readable(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        if (t instanceof java.net.UnknownHostException) return "连不上 GitHub，检查网络后重试";
        if (t instanceof java.net.SocketTimeoutException) return "网络超时，稍后再试";
        return m;
    }

    private void emitInfo(final String json) {
        emit("window.__updateInfo && window.__updateInfo(" + quote(json) + ");");
    }

    private void emitProgress(final int pct) {
        emit("window.__updateProgress && window.__updateProgress(" + pct + ");");
    }

    private void emitDone(final String msg) {
        emit("window.__updateDone && window.__updateDone(" + quote(msg) + ");");
    }

    private void emit(final String code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                js.call(code);
            }
        });
    }

    static String quote(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.append('\'').toString();
    }
}
