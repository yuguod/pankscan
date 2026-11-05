package com.pankscan.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA_PERM = 1002;

    private WebView web;
    private FrameLayout root;
    private ScanOverlay overlay;
    private UpdateManager updater;
    private ValueCallback<Uri[]> filePathCallback;
    private final Map<String, String> mimeMap = new HashMap<String, String>();

    /** 网页侧是否有弹窗开着（由 JS 通过 setModalOpen 同步），返回键据此先关弹窗 */
    private volatile boolean modalOpen;
    private long lastBackMs;
    private boolean exitShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mimeMap.put("html", "text/html");
        mimeMap.put("js", "application/javascript");
        mimeMap.put("css", "text/css");
        mimeMap.put("json", "application/json");
        mimeMap.put("webmanifest", "application/manifest+json");
        mimeMap.put("png", "image/png");
        mimeMap.put("svg", "image/svg+xml");

        boolean needPerm = false;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needPerm = true;
        }

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new ScanOverlay(this);
        updater = new UpdateManager(this, new UpdateManager.JsBridge() {
            @Override
            public void call(String js) {
                jsCall(js);
            }
        });
        overlay.setListener(new ScanOverlay.Listener() {
            @Override
            public void onScan(String text) {
                jsCall("window.__nativeScan(" + jsString(text) + ");");
            }

            @Override
            public void onStopped() {
                jsCall("window.__scanStopped && window.__scanStopped();");
            }

            @Override
            public void onError(String msg) {
                jsCall("window.__scanError(" + jsString(msg) + ");");
            }
        });

        root = new FrameLayout(this);
        root.addView(web);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (needPerm) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
        } else {
            web.loadUrl(BASE + "index.html");
        }
    }

    private String jsString(String s) {
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

    private void jsCall(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                try {
                    if (Build.VERSION.SDK_INT >= 19) {
                        web.evaluateJavascript(code, null);
                    } else {
                        web.loadUrl("javascript:" + code);
                    }
                } catch (Exception ignore) {
                }
            }
        });
    }

    private String guessMime(String name) {
        int i = name.lastIndexOf('.');
        if (i >= 0) {
            String ext = name.substring(i + 1).toLowerCase();
            String m = mimeMap.get(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (!url.startsWith(BASE)) return null;
            String path = request.getUrl().getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            String name = path.substring(1);
            if (name.indexOf("..") >= 0) return null;
            try {
                InputStream in = getAssets().open(name);
                WebResourceResponse r = new WebResourceResponse(guessMime(name), null, in);
                Map<String, String> h = new HashMap<String, String>();
                h.put("Cache-Control", "no-cache");
                r.setResponseHeaders(h);
                return r;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith(BASE)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignore) {
            }
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    boolean granted = Build.VERSION.SDK_INT < 23
                            || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    if (granted) {
                        request.grant(request.getResources());
                    } else {
                        request.deny();
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                    }
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE);
                return true;
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERM && web != null) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, "未授权摄像头，扫码无法使用", Toast.LENGTH_LONG).show();
            }
            if (web.getUrl() == null) {
                web.loadUrl(BASE + "index.html");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (overlay != null) overlay.releaseCamera();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (overlay != null) overlay.resume();
    }

    @Override
    protected void onDestroy() {
        if (overlay != null) overlay.releaseCamera();
        if (web != null) {
            try {
                web.destroy();
            } catch (Exception ignore) {
            }
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * 返回键的三条出路。
     *
     * 这台 App 没有 URL 路由，`canGoBack()` 一直是 false，所以以前不管停在哪个界面
     * ——正在扫码、刚打开操作说明——按一下返回就直接退出程序，摄像头还开着。
     * 现在按「当前在做什么」分流：扫码中先退扫码、有弹窗先关弹窗，最后才是退出确认。
     */
    private void handleBack() {
        long now = System.currentTimeMillis();
        // 个别机型会把同一个返回键同时送进 onKeyDown 和 onBackPressed，去重
        if (now - lastBackMs < 400) return;
        lastBackMs = now;

        if (overlay != null && overlay.isActive()) {
            overlay.requestStop();
            return;
        }
        if (modalOpen) {
            // 网页侧开着弹窗（操作说明、编辑记录等），让它自己收
            jsCall("window.__backToClose && window.__backToClose();");
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        confirmExit();
    }

    /**
     * 退出前先问一句，并且把「本机还有多少条」写进提示里。
     * 现场最怕的是扫了一上午、以为退出会清空，于是不敢退；反过来也怕误触一下
     * 退出去以为数据没了。数字摆在眼前，这两个顾虑一起解决。
     */
    private void confirmExit() {
        if (web == null) {
            showExitDialog(-1);
            return;
        }
        String js = "(function(){try{var s=JSON.parse(localStorage.getItem('pankscan.v3')||'{}');"
                + "return (s.records||[]).length;}catch(e){return -1;}})()";
        try {
            web.evaluateJavascript(js, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    int n = -1;
                    try {
                        n = Integer.parseInt(v.trim());
                    } catch (Exception ignore) {
                    }
                    showExitDialog(n);
                }
            });
        } catch (Throwable t) {
            showExitDialog(-1);
        }
    }

    private void showExitDialog(int count) {
        if (isFinishing() || exitShown) return;
        exitShown = true;

        String msg;
        if (count > 0) {
            msg = "本机存着 " + count + " 条记录，退出后仍然保留，下次打开接着用。";
        } else if (count == 0) {
            msg = "当前还没有记录。退出后随时可以再打开继续扫。";
        } else {
            msg = "记录都存在本机，退出后不会丢，下次打开接着用。";
        }

        new AlertDialog.Builder(this)
                .setTitle("退出盘库助手？")
                .setMessage(msg)
                .setNegativeButton("再想想", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        exitShown = false;
                    }
                })
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        exitShown = false;
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        exitShown = false;
                    }
                })
                .show();
    }

    public class Bridge {

        @JavascriptInterface
        public void startScan(final boolean qr) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 23
                            && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                        jsCall("window.__scanError('未授权摄像头，请在系统设置里允许后重试');");
                        return;
                    }
                    overlay.begin(qr);
                }
            });
        }

        @JavascriptInterface
        public void stopScan() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    overlay.stop();
                }
            });
        }

        @JavascriptInterface
        public void setScanCount(final int n) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    overlay.setCount(n);
                }
            });
        }

        /**
         * 网页侧弹窗开关同步。返回键只在真正无处可退时才问「退出」，
         * 有弹窗时先让网页把弹窗收掉。
         */
        @JavascriptInterface
        public void setModalOpen(boolean open) {
            modalOpen = open;
        }

        /** 网页判定完成后回传结果：1 已记录 / 0 重复 / 2 未设栈板 / 3 忽略 */
        @JavascriptInterface
        public void scanFeedback(final int code, final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    overlay.report(code, text);
                }
            });
        }

        /** 同步网页里的「声音 / 震动」开关 */
        @JavascriptInterface
        public void setFeedback(final boolean sound, final boolean vibe) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    overlay.setFeedback(sound, vibe);
                }
            });
        }

        /* ---------- 自动更新 ---------- */

        /** 检查 GitHub 上的 update.json；结果通过 window.__updateInfo 回到网页 */
        @JavascriptInterface
        public void checkUpdate() {
            updater.check();
        }

        /** 下载并交给系统安装器 */
        @JavascriptInterface
        public void downloadUpdate() {
            updater.downloadAndInstall();
        }

        /* ---------- 本机识别画像 ---------- */

        @JavascriptInterface
        public String getScanProfile() {
            return overlay.profile().toJson();
        }

        @JavascriptInterface
        public void resetScanProfile() {
            overlay.resetProfile();
        }

        /** 网页侧从已入账记录学到的 SN 前缀/长度，用来收紧密码格式白名单 */
        @JavascriptInterface
        public void setSnHint(String prefix, int len) {
            overlay.profile().setSnHint(prefix, len);
            overlay.profile().flush();
        }

        @JavascriptInterface
        public void toast(String msg) {
            final String m = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String saveFile(String base64, String name, String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "err:insert";
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(data);
                    os.flush();
                    os.close();
                    return "ok";
                }
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.flush();
                fo.close();
                return "ok";
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String shareFile(String base64, String name, String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                final File f = new File(dir, name);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.flush();
                fo.close();

                final Uri uri = Uri.parse("content://com.pankscan.app.files/" + Uri.encode(name));
                final String type = mime == null || mime.length() == 0
                        ? "application/octet-stream" : mime;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent i = new Intent(Intent.ACTION_SEND);
                            i.setType(type);
                            i.putExtra(Intent.EXTRA_STREAM, uri);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(i, "分享到"));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "分享失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
                return "ok";
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }
    }
}
