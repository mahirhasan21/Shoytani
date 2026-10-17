package com.ariyan.shoytani;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String[][] SITES = {
            {"Facebook", "https://m.facebook.com", "m"},
            {"Messenger", "https://www.messenger.com", "d"},
            {"Telegram", "https://web.telegram.org/k/", "m"},
            {"WhatsApp", "https://web.whatsapp.com", "d"},
            {"Instagram", "https://www.instagram.com", "m"},
    };
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int REQ_MIC = 77;
    private static final int PINK = Color.parseColor("#FF2D95");
    private static final int BG = Color.parseColor("#0B0B14");

    private WebView web;
    private LinearLayout drawer;
    private View scrim;
    private TextView title, micBtn;
    private boolean boostOn = true;
    private PermissionRequest pendingWebPerm;
    private String injectorJs = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        injectorJs = readAsset("injector.js");
        buildUi();
        setupWebView();
        openSite(1); // Messenger first
    }

    // ---------------- UI (built in code, no XML) ----------------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView barButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);

        TextView menu = barButton("☰", v -> toggleDrawer());
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        micBtn = barButton("🎙", v -> onMicTap());
        TextView more = barButton("⋮", v -> showInfoBox());
        bar.addView(menu);
        bar.addView(title);
        bar.addView(micBtn);
        bar.addView(more);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        web = new WebView(this);
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> toggleDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(Color.parseColor("#14142A"));
        drawer.setPadding(0, dp(24), 0, 0);
        drawer.setVisibility(View.GONE);
        TextView head = new TextView(this);
        head.setText("♛ SHOYTANI SHOKTI");
        head.setTextColor(PINK);
        head.setTextSize(18);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(dp(20), dp(8), dp(20), dp(16));
        drawer.addView(head);
        for (int i = 0; i < SITES.length; i++) {
            final int idx = i;
            TextView item = new TextView(this);
            item.setText(SITES[i][0]);
            item.setTextColor(Color.WHITE);
            item.setTextSize(17);
            item.setPadding(dp(20), dp(16), dp(20), dp(16));
            item.setOnClickListener(v -> { openSite(idx); toggleDrawer(); });
            drawer.addView(item);
        }
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(260), -1);
        root.addView(drawer, dlp);
        setContentView(root);
        refreshMic();
    }

    private void toggleDrawer() {
        boolean show = drawer.getVisibility() != View.VISIBLE;
        drawer.setVisibility(show ? View.VISIBLE : View.GONE);
        scrim.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void refreshMic() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(boostOn ? PINK : Color.parseColor("#444455"));
        micBtn.setBackground(g);
        micBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        micBtn.setTextSize(18);
    }

    private void showInfoBox() {
        String text = "SHOYTANI SHOKTI — by ARIYAN\n\n"
                + "☰  সাইড মেনু: Facebook, Messenger, Telegram, WhatsApp, Instagram\n"
                + "🎙  মাইক: টিপলে মাইক খুলবে + ভয়েস বুস্ট ON/OFF\n\n"
                + "Voice boost: " + (boostOn ? "ON" : "OFF")
                + "\n\nCall না না হলে পেজ রিলোড করে আবার চেষ্টা করো।";
        new AlertDialog.Builder(this).setTitle("SHOYTANI SHOKTI").setMessage(text)
                .setPositiveButton("OK", null).show();
    }

    // ---------------- WebView ----------------
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> origins = new HashSet<>(Collections.singletonList("*"));
            WebViewCompat.addDocumentStartJavaScript(web, wrapper(), origins);
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                    v.evaluateJavascript(wrapper(), null);
            }
            @Override public void onPageFinished(WebView v, String url) { pushConfig(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pendingWebPerm = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQ_MIC);
                    }
                });
            }
        });
    }

    private String wrapper() {
        return "(function(){if(window.__shWrapped)return;window.__shWrapped=true;"
                + "window.__shCfg={enabled:" + boostOn + "};"
                + injectorJs
                + "\n;setInterval(function(){window.postMessage({type:'MIC_MAXIMIZER_CONFIG',payload:window.__shCfg},'*');},2500);"
                + "setTimeout(function(){window.postMessage({type:'MIC_MAXIMIZER_CONFIG',payload:window.__shCfg},'*');},100);})();";
    }

    private void pushConfig() {
        web.evaluateJavascript("(function(){window.__shCfg=window.__shCfg||{};window.__shCfg.enabled="
                + boostOn + ";window.postMessage({type:'MIC_MAXIMIZER_CONFIG',payload:window.__shCfg},'*');})();", null);
    }

    private void openSite(int i) {
        title.setText(SITES[i][0]);
        boolean desktop = "d".equals(SITES[i][2]);
        web.getSettings().setUserAgentString(desktop ? DESKTOP_UA : null);
        web.loadUrl(SITES[i][1]);
    }

    // ---------------- Mic ----------------
    private void onMicTap() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQ_MIC);
            return;
        }
        boostOn = !boostOn;
        refreshMic();
        pushConfig();
        Toast.makeText(this, "Mic boost " + (boostOn ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_MIC) return;
        boolean ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (pendingWebPerm != null) {
            if (ok) pendingWebPerm.grant(pendingWebPerm.getResources()); else pendingWebPerm.deny();
            pendingWebPerm = null;
        }
        Toast.makeText(this, ok ? "Mic ready" : "Mic permission denied", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) toggleDrawer();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }
}
