package com.telegallery.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;
    private BackupPrefs backupPrefs;
    private BroadcastReceiver backupReceiver;

    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_CAMERA       = 1002;
    private static final int REQUEST_PERMISSIONS  = 1003;

    /* ════════════════════════════════
       JS ↔ Android Bridge
    ════════════════════════════════ */
    public class AndroidBridge {

        /** JS-এ কোনো screen/modal খোলা না থাকলে app বন্ধ */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> finish());
        }

        /** Native share sheet */
        @JavascriptInterface
        public void shareUrl(String url, String title) {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, url);
                intent.putExtra(Intent.EXTRA_SUBJECT, title);
                startActivity(Intent.createChooser(intent, "শেয়ার করুন"));
            });
        }

        /** Auto backup toggle from JS */
        @JavascriptInterface
        public void setBackupEnabled(boolean enabled, String token, String chatId) {
            runOnUiThread(() -> {
                backupPrefs.setCredentials(token, chatId);
                backupPrefs.setBackupEnabled(enabled);

                if (enabled) {
                    requestBackupPermissions();
                } else {
                    stopService(new Intent(MainActivity.this, AutoBackupService.class));
                    Toast.makeText(MainActivity.this, "Auto Backup বন্ধ", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** Start initial (full) backup of all existing photos */
        @JavascriptInterface
        public void startInitialBackup(String token, String chatId) {
            runOnUiThread(() -> {
                backupPrefs.setCredentials(token, chatId);
                backupPrefs.setBackupEnabled(true);
                backupPrefs.clearBackedUpIds();
                Intent svc = new Intent(MainActivity.this, AutoBackupService.class);
                svc.setAction("INITIAL_BACKUP");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                Toast.makeText(MainActivity.this,
                    "সব ছবির Backup শুরু হয়েছে…", Toast.LENGTH_SHORT).show();
            });
        }

        /** Get backup status */
        @JavascriptInterface
        public boolean isBackupEnabled() {
            return backupPrefs.isBackupEnabled();
        }
    }

    /* ════════════════════════════════
       Lifecycle
    ════════════════════════════════ */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
            getWindow().setNavigationBarColor(0xFF000000);
        }
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_main);

        backupPrefs = new BackupPrefs(this);
        webView = findViewById(R.id.webview);
        setupWebView();
        registerBackupReceiver();
        requestBasePermissions();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccessFromFileURLs(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("file://") || url.startsWith("https://api.telegram.org"))
                    return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored) {}
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb,
                    FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                if (params.isCaptureEnabled()) launchCamera();
                else launchGallery();
                return true;
            }
            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("হ্যাঁ", (d, w) -> r.confirm())
                    .setNegativeButton("না",   (d, w) -> r.cancel())
                    .setCancelable(false).show();
                return true;
            }
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("ঠিক আছে", (d, w) -> r.confirm())
                    .setCancelable(false).show();
                return true;
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) { return true; }
        });
    }

    /* ════════════════════════════════
       Broadcast receiver — backup → JS
    ════════════════════════════════ */
    private void registerBackupReceiver() {
        backupReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("com.telegallery.BACKUP_PROGRESS".equals(intent.getAction())) {
                    int done  = intent.getIntExtra("done", 0);
                    int total = intent.getIntExtra("total", 0);
                    String cur = intent.getStringExtra("current");
                    if (cur == null) cur = "";
                    final String js = String.format(
                        "if(typeof onBackupProgress==='function')onBackupProgress(%d,%d,'%s');",
                        done, total, cur.replace("'", "\\'"));
                    final String jsf = js;
                    runOnUiThread(() -> webView.evaluateJavascript(jsf, null));
                } else if ("com.telegallery.PHOTO_UPLOADED".equals(intent.getAction())) {
                    String url   = intent.getStringExtra("url");
                    String album = intent.getStringExtra("album");
                    if (url == null) url = "";
                    if (album == null) album = "";
                    final String js2 = String.format(
                        "if(typeof onPhotoAutoUploaded==='function')onPhotoAutoUploaded('%s','%s');",
                        url.replace("'", "\\'"), album.replace("'", "\\'"));
                    runOnUiThread(() -> webView.evaluateJavascript(js2, null));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.telegallery.BACKUP_PROGRESS");
        filter.addAction("com.telegallery.PHOTO_UPLOADED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(backupReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(backupReceiver, filter);
        }
    }

    /* ════════════════════════════════
       Camera / Gallery
    ════════════════════════════════ */
    private void launchCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "ক্যামেরা পাওয়া যায়নি", Toast.LENGTH_SHORT).show();
            cancelCb(); return;
        }
        File f = createImageFile();
        if (f == null) { cancelCb(); return; }
        cameraImageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
        startActivityForResult(intent, REQUEST_CAMERA);
    }

    private void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "ছবি বেছে নিন"), REQUEST_FILE_CHOOSER);
    }

    private File createImageFile() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            return File.createTempFile("IMG_" + ts, ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES));
        } catch (IOException e) { return null; }
    }

    private void cancelCb() {
        if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (filePathCallback == null) return;
        Uri[] results = null;
        if (res == Activity.RESULT_OK) {
            if (req == REQUEST_CAMERA && cameraImageUri != null) {
                results = new Uri[]{ cameraImageUri };
            } else if (req == REQUEST_FILE_CHOOSER && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraImageUri = null;
    }

    /* ════════════════════════════════
       Permissions
    ════════════════════════════════ */
    private void requestBasePermissions() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.READ_MEDIA_IMAGES);
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        List<String> toRequest = new ArrayList<>();
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                toRequest.add(p);
        }
        if (!toRequest.isEmpty())
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private void requestBackupPermissions() {
        requestBasePermissions();
        // After permission granted, start service
        Intent svc = new Intent(this, AutoBackupService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
        Toast.makeText(this, "Auto Backup চালু হয়েছে ✓", Toast.LENGTH_SHORT).show();
    }

    /* ════════════════════════════════
       Back button
    ════════════════════════════════ */
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("handleAndroidBack()", null);
    }

    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onPause()  { super.onPause();  webView.onPause(); }

    @Override
    protected void onDestroy() {
        if (backupReceiver != null) try { unregisterReceiver(backupReceiver); } catch (Exception ignored) {}
        if (webView != null) { webView.removeJavascriptInterface("Android"); webView.destroy(); }
        super.onDestroy();
    }
}
