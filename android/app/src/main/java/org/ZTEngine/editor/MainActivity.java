package org.ZTEngine.editor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.browser.customtabs.CustomTabsIntent;
import com.getcapacitor.BridgeActivity;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {

    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_RESULT_CODE = 1;
    private static final int CREATE_FILE_RESULT_CODE = 2;

    private String pendingBase64Data;
    // 仅新增这一个变量用于防抖
    private long lastSaveTime = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWebView(this.bridge.getWebView(), true);
    }

    private void setupWebView(WebView webView, boolean isMain) {
        webView.getSettings().setSupportMultipleWindows(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        // 桥接：调起系统“另存为”
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void triggerSaveAs(String base64Data, String fileName) {
                // 防抖逻辑：1秒内重复触发则跳过
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSaveTime < 1000) {
                    return;
                }
                lastSaveTime = currentTime;

                pendingBase64Data = base64Data;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(intent, CREATE_FILE_RESULT_CODE);
            }
        }, "AndroidDownloadBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                MainActivity.this.filePathCallback = filePathCallback;
                startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_RESULT_CODE);
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView.HitTestResult result = view.getHitTestResult();
                String url = result.getExtra();
                if (url != null) { handleUrl(url); return false; }
                
                WebView tempWebView = new WebView(view.getContext());
                tempWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        handleUrl(request.getUrl().toString());
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(tempWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });

        if (isMain) {
            webView.setWebViewClient(new com.getcapacitor.BridgeWebViewClient(this.bridge) {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    injectSaveInterceptor(view);
                }
            });
        }
    }

    private void injectSaveInterceptor(WebView view) {
        view.evaluateJavascript(
            "(function() { " +
            "  window.addEventListener('click', e => { " +
            "    const a = e.target.closest('a'); " +
            "    if (a && (a.download || a.href.startsWith('blob:'))) { " +
            "      e.preventDefault(); " +
            "      fetch(a.href).then(r => r.blob()).then(b => { " +
            "        const reader = new FileReader(); " +
            "        reader.onloadend = () => window.AndroidDownloadBridge.triggerSaveAs(reader.result, a.download || 'project.sb3'); " +
            "        reader.readAsDataURL(b); " +
            "      }); " +
            "    } " +
            "  }, true); " +
            "})();", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
        if (requestCode == CREATE_FILE_RESULT_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null && pendingBase64Data != null) {
                try {
                    String base64 = pendingBase64Data.substring(pendingBase64Data.indexOf(",") + 1);
                    try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                        os.write(Base64.decode(base64, Base64.DEFAULT));
                    }
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                }
            }
            pendingBase64Data = null; // 无论成功失败都清理内存
        }
    }

    private void handleUrl(String url) {
        if (url.contains("localhost")) {
            showLocalSubWindow(url);
        } else {
            new CustomTabsIntent.Builder().setToolbarColor(0xFF333333).build().launchUrl(this, Uri.parse(url));
        }
    }

    private void showLocalSubWindow(String url) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        WebView subWebView = new WebView(this);
        setupWebView(subWebView, false);
        subWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl().getHost().equals("localhost")) return bridge.getLocalServer().shouldInterceptRequest(request);
                return super.shouldInterceptRequest(view, request);
            }
            @Override
            public void onPageFinished(WebView view, String url) { injectSaveInterceptor(view); }
        });
        subWebView.loadUrl(url);
        dialog.setContentView(subWebView, new ViewGroup.LayoutParams(-1, -1));
        dialog.show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onStart() {
        super.onStart();
        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.setVerticalScrollBarEnabled(false);
            webView.setHorizontalScrollBarEnabled(false);
            webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }
}