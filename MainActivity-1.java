package com.ariyan.shoytani;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    private static final int REQ_ZIP = 78;
    private ScriptHandler scriptHandler;
    private static final int PINK = Color.parseColor("#FF2D95");
    private static final int BG = Color.parseColor("#0B0B14");

    private WebView web;
    private LinearLayout drawer;
    private View scrim;
    private TextView title, micBtn;
    private boolean boostOn = true;
    private PermissionRequest pendingWebPerm;
    private String injectorJs = "";
    private LinearLayout panel;
    private TextView micLabel, callLabel, meterLabel;
    private ProgressBar meterBar;
    private Button openMicBtn;
    private int micBoostVal = 4, callGainVal = 1;
    private volatile boolean metering = false;
    private boolean wantPanelAfterPerm = false, wantMeterAfterPerm = false;

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
        TextView extItem = new TextView(this);
        extItem.setText("\uD83E\uDDE9 Extensions (add .zip)");
        extItem.setTextColor(PINK);
        extItem.setTextSize(16);
        extItem.setPadding(dp(20), dp(24), dp(20), dp(16));
        extItem.setOnClickListener(v -> { toggleDrawer(); showExtDialog(); });
        drawer.addView(extItem);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(260), -1);
        root.addView(drawer, dlp);
        buildPanel();
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(panel, plp);
        setContentView(root);
        refreshMic();
    }

    private double gainFromSeek(int v) { return Math.round(Math.pow(1000.0, v / 100.0)); }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#14142A"));
        bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        TextView t = new TextView(this);
        t.setText("\uD83C\uDF99 MIC STUDIO");
        t.setTextColor(PINK);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(t);

        Switch sw = new Switch(this);
        sw.setText("Voice boost ON / OFF");
        sw.setTextColor(Color.WHITE);
        sw.setChecked(boostOn);
        sw.setPadding(0, dp(10), 0, dp(6));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            boostOn = on; refreshMic(); pushConfig();
        });
        panel.addView(sw);

        micLabel = new TextView(this);
        micLabel.setTextColor(Color.WHITE);
        panel.addView(micLabel);
        SeekBar micSeek = new SeekBar(this);
        micSeek.setMax(100);
        micSeek.setProgress(20);
        micSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                micBoostVal = (int) gainFromSeek(p);
                micLabel.setText("Mic boost: " + micBoostVal + "x");
                if (u) pushConfig();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { pushConfig(); }
        });
        panel.addView(micSeek);
        micLabel.setText("Mic boost: " + micBoostVal + "x");

        callLabel = new TextView(this);
        callLabel.setTextColor(Color.WHITE);
        callLabel.setPadding(0, dp(8), 0, 0);
        panel.addView(callLabel);
        SeekBar callSeek = new SeekBar(this);
        callSeek.setMax(100);
        callSeek.setProgress(0);
        callSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                callGainVal = (int) gainFromSeek(p);
                callLabel.setText("Call sound: " + callGainVal + "x");
                if (u) pushConfig();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { pushConfig(); }
        });
        panel.addView(callSeek);
        callLabel.setText("Call sound: " + callGainVal + "x");

        openMicBtn = new Button(this);
        openMicBtn.setText("\uD83C\uDF99 Open Mic (test)");
        openMicBtn.setOnClickListener(v -> {
            if (metering) { stopMeter(); return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                wantMeterAfterPerm = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQ_MIC);
            } else startMeter();
        });
        panel.addView(openMicBtn);

        meterBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meterBar.setMax(100);
        panel.addView(meterBar, new LinearLayout.LayoutParams(-1, dp(14)));
        meterLabel = new TextView(this);
        meterLabel.setTextColor(Color.parseColor("#AAAACC"));
        meterLabel.setTextSize(12);
        meterLabel.setText("Mic closed. Close it before starting a call.");
        panel.addView(meterLabel);

        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> hidePanel());
        panel.addView(close);
    }

    private void showPanel() { panel.setVisibility(View.VISIBLE); }

    private void hidePanel() { stopMeter(); panel.setVisibility(View.GONE); }

    private void startMeter() {
        if (metering) return;
        final int rate = 16000;
        final int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { Toast.makeText(this, "Mic not available", Toast.LENGTH_SHORT).show(); return; }
        final AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, min * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); throw new IllegalStateException(); }
            rec.startRecording();
        } catch (Exception e) {
            Toast.makeText(this, "Mic could not open", Toast.LENGTH_SHORT).show();
            return;
        }
        metering = true;
        openMicBtn.setText("\u23F9 Close Mic");
        meterLabel.setText("Mic is OPEN. Speak to see the level.");
        new Thread(() -> {
            short[] buf = new short[1024];
            while (metering) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) {
                    double sum = 0;
                    for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                    double rms = Math.sqrt(sum / n);
                    final int lvl = (int) Math.min(100, rms / 32768.0 * 500.0);
                    meterBar.post(() -> meterBar.setProgress(lvl));
                }
            }
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        }).start();
    }

    private void stopMeter() {
        if (!metering) return;
        metering = false;
        openMicBtn.setText("\uD83C\uDF99 Open Mic (test)");
        meterBar.setProgress(0);
        meterLabel.setText("Mic closed. Close it before starting a call.");
    }

    @Override protected void onPause() { super.onPause(); stopMeter(); }

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

        registerScript();
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

    private void registerScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try {
            if (scriptHandler != null) scriptHandler.remove();
            Set<String> origins = new HashSet<>(Collections.singletonList("*"));
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, wrapper(), origins);
        } catch (Exception e) {
            Toast.makeText(this, "Script error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String wrapper() {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){if(window.__shWrapped)return;window.__shWrapped=true;")
          .append("window.__shCfg={enabled:").append(boostOn).append(",micBoost:").append(micBoostVal)
          .append(",listenGain:").append(callGainVal).append("};window.__shFiles=window.__shFiles||{};");
        List<Ext> exts = loadExts();
        if (exts.isEmpty()) {
            sb.append(injectorJs)
              .append("\n;setInterval(function(){window.postMessage({type:'MIC_MAXIMIZER_CONFIG',payload:window.__shCfg},'*');},2500);")
              .append("setTimeout(function(){window.postMessage({type:'MIC_MAXIMIZER_CONFIG',payload:window.__shCfg},'*');},100);");
        } else {
            sb.append(HOOK_JS);
            for (Ext e : exts) {
                try { sb.append(extScript(e)); } catch (Exception ex) { /* skip broken extension */ }
            }
        }
        sb.append("})();");
        return sb.toString();
    }

    // ---------------- Extensions (zip) ----------------
    private static final String HOOK_JS = """
function __m(p){for(var i=0;i<p.length;i++){try{if(new RegExp(p[i]).test(location.href))return true;}catch(e){}}return false;}
function __ready(f){var n=0;(function t(){if(document.documentElement||n++>400){try{f();}catch(e){}}else setTimeout(t,5);})();}
function __css(t){__ready(function(){var s=document.createElement('style');s.textContent=t;(document.head||document.documentElement).appendChild(s);});}
var __ap=Node.prototype.appendChild,__ib=Node.prototype.insertBefore;
function __ext(n){try{if(n&&n.tagName==='SCRIPT'&&n.src&&window.__shFiles[n.src]){var f=window.__shFiles[n.src];setTimeout(function(){try{f();}catch(e){}if(n.onload)try{n.onload();}catch(e){}},0);return true;}}catch(e){}return false;}
Node.prototype.appendChild=function(n){if(__ext(n))return n;return __ap.call(this,n);};
Node.prototype.insertBefore=function(n,r){if(__ext(n))return n;return __ib.call(this,n,r);};
""";

    private static final String SHIM_JS = """
var store=(function(){try{return JSON.parse(localStorage.getItem('__shx_'+__id)||'{}');}catch(e){return {};}})();
function save(){try{localStorage.setItem('__shx_'+__id,JSON.stringify(store));}catch(e){}}
function view(){var o=JSON.parse(JSON.stringify(store));var c=window.__shCfg||{};var m=o.micMaximizerConfig||{};
if(m.forceRawMic===undefined)m.forceRawMic=true;m.enabled=c.enabled!==false;if(c.micBoost)m.micBoost=c.micBoost;if(c.listenGain)m.listenGain=c.listenGain;
o.micMaximizerConfig=m;return o;}
function pick(keys){var v=view(),r={};if(keys==null)return v;
if(typeof keys==='string'){if(keys in v)r[keys]=v[keys];return r;}
if(Array.isArray(keys)){keys.forEach(function(k){if(k in v)r[k]=v[k];});return r;}
Object.keys(keys).forEach(function(k){r[k]=(k in v)?v[k]:keys[k];});return r;}
var noop={addListener:function(){},removeListener:function(){},hasListener:function(){return false;}};
var shim={runtime:{id:__id,getURL:function(p){p=String(p);return __base+(p.charAt(0)==='/'?p.slice(1):p);},
sendMessage:function(m,cb){if(typeof cb==='function')setTimeout(function(){cb({ok:true});},0);return Promise.resolve({ok:true});},
onMessage:noop,onInstalled:noop,lastError:undefined,getManifest:function(){return {};}},
storage:{local:{get:function(keys,cb){var r=pick(keys);if(typeof cb==='function'){setTimeout(function(){cb(r);},0);return;}return Promise.resolve(r);},
set:function(o,cb){Object.keys(o).forEach(function(k){store[k]=o[k];});save();if(typeof cb==='function')setTimeout(cb,0);return Promise.resolve();},
remove:function(k,cb){[].concat(k).forEach(function(x){delete store[x];});save();if(typeof cb==='function')setTimeout(cb,0);return Promise.resolve();}},onChanged:noop},
tabs:{query:function(q,cb){if(typeof cb==='function')cb([]);return Promise.resolve([]);}}};
function __use(){try{Object.defineProperty(window,'chrome',{value:shim,configurable:true,writable:true});}catch(e){try{window.chrome=shim;}catch(e2){}}}
""";

    private static class Ext { String id, name; File dir; JSONObject manifest; }

    private File extRoot() { return new File(getFilesDir(), "ext"); }

    private List<Ext> loadExts() {
        List<Ext> list = new ArrayList<>();
        File[] dirs = extRoot().listFiles();
        if (dirs == null) return list;
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            try {
                File mf = new File(d, "manifest.json");
                if (!mf.isFile()) continue;
                Ext e = new Ext();
                e.id = d.getName();
                e.dir = d;
                e.manifest = new JSONObject(new String(readFileBytes(mf), StandardCharsets.UTF_8));
                e.name = e.manifest.optString("name", d.getName());
                list.add(e);
            } catch (Exception ignored) { }
        }
        return list;
    }

    private static byte[] readFileBytes(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { return readAll(in); }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static String toRegex(String p) {
        if ("<all_urls>".equals(p)) return "^(https?|file)://.*$";
        StringBuilder r = new StringBuilder("^");
        for (char c : p.toCharArray()) {
            if (c == '*') r.append(".*");
            else if (".?+()[]{}|^$\\".indexOf(c) >= 0) r.append('\\').append(c);
            else r.append(c);
        }
        return r.append('$').toString();
    }

    private void collectJs(File dir, String rel, Map<String, File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) collectJs(f, r, out);
            else if (r.endsWith(".js") && f.length() < 6L * 1024 * 1024) out.put(r, f);
        }
    }

    private String extScript(Ext e) throws Exception {
        String base = "https://ext.local/" + e.id + "/";
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){var __id=").append(JSONObject.quote(e.id))
          .append(",__base=").append(JSONObject.quote(base)).append(";\n").append(SHIM_JS);
        Map<String, File> js = new LinkedHashMap<>();
        collectJs(e.dir, "", js);
        for (Map.Entry<String, File> en : js.entrySet()) {
            sb.append("window.__shFiles[").append(JSONObject.quote(base + en.getKey())).append("]=function(){\n")
              .append(new String(readFileBytes(en.getValue()), StandardCharsets.UTF_8)).append("\n};\n");
        }
        JSONArray cs = e.manifest.optJSONArray("content_scripts");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.getJSONObject(i);
                JSONArray matches = c.optJSONArray("matches");
                if (matches == null) continue;
                JSONArray rx = new JSONArray();
                for (int k = 0; k < matches.length(); k++) rx.put(toRegex(matches.getString(k)));
                sb.append("if(__m(").append(rx.toString()).append(")){__use();");
                JSONArray jsList = c.optJSONArray("js");
                if (jsList != null) {
                    for (int k = 0; k < jsList.length(); k++) {
                        String key = base + jsList.getString(k);
                        sb.append("__ready(function(){try{window.__shFiles[").append(JSONObject.quote(key)).append("]();}catch(e){}});");
                    }
                }
                JSONArray cssList = c.optJSONArray("css");
                if (cssList != null) {
                    for (int k = 0; k < cssList.length(); k++) {
                        File cf = new File(e.dir, cssList.getString(k));
                        if (cf.isFile()) sb.append("__css(").append(JSONObject.quote(new String(readFileBytes(cf), StandardCharsets.UTF_8))).append(");");
                    }
                }
                sb.append("}\n");
            }
        }
        sb.append("})();\n");
        return sb.toString();
    }

    private void showExtDialog() {
        final List<Ext> exts = loadExts();
        String[] items = new String[exts.size() + 1];
        items[0] = "\uFF0B Add extension (.zip)";
        for (int i = 0; i < exts.size(); i++) items[i + 1] = "\u2715 Remove: " + exts.get(i).name;
        new AlertDialog.Builder(this).setTitle("Extensions")
                .setItems(items, (d, w) -> { if (w == 0) pickZip(); else removeExt(exts.get(w - 1)); })
                .setNegativeButton("Close", null).show();
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_ZIP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ZIP && res == RESULT_OK && data != null && data.getData() != null) importZip(data.getData());
    }

    private void toast(final String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    private void importZip(final Uri uri) {
        toast("Installing extension...");
        new Thread(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                Map<String, byte[]> files = new LinkedHashMap<>();
                ZipInputStream z = new ZipInputStream(in);
                ZipEntry en;
                long total = 0;
                while ((en = z.getNextEntry()) != null) {
                    if (en.isDirectory()) continue;
                    byte[] b = readAll(z);
                    total += b.length;
                    if (total > 80L * 1024 * 1024) throw new IOException("zip too big");
                    files.put(en.getName(), b);
                }
                String mp = null;
                for (String k : files.keySet()) {
                    if (k.equals("manifest.json") || k.endsWith("/manifest.json")) {
                        if (mp == null || k.length() < mp.length()) mp = k;
                    }
                }
                if (mp == null) { toast("manifest.json not found in zip"); return; }
                String prefix = mp.substring(0, mp.length() - "manifest.json".length());
                String id = "e" + System.currentTimeMillis();
                File dir = new File(extRoot(), id);
                String dirPath = dir.getCanonicalPath() + File.separator;
                for (Map.Entry<String, byte[]> f : files.entrySet()) {
                    if (!f.getKey().startsWith(prefix)) continue;
                    File out = new File(dir, f.getKey().substring(prefix.length()));
                    if (!out.getCanonicalPath().startsWith(dirPath)) continue;
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(f.getValue()); }
                }
                runOnUiThread(() -> {
                    registerScript();
                    web.reload();
                    Toast.makeText(this, "Extension added", Toast.LENGTH_LONG).show();
                });
            } catch (Exception ex) {
                toast("Could not add extension: " + ex.getMessage());
            }
        }).start();
    }

    private void removeExt(Ext e) {
        deleteRec(e.dir);
        registerScript();
        web.reload();
        Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show();
    }

    private void deleteRec(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteRec(x);
        f.delete();
    }

    private void pushConfig() {
        web.evaluateJavascript("(function(){window.__shCfg=window.__shCfg||{};window.__shCfg.enabled="
                + boostOn + ";window.__shCfg.micBoost=" + micBoostVal + ";window.__shCfg.listenGain=" + callGainVal + ";window.postMessage({type:'MIC_MAXIMIZER_CONFIG',payload:window.__shCfg},'*');})();", null);
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
            wantPanelAfterPerm = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQ_MIC);
            return;
        }
        if (panel.getVisibility() == View.VISIBLE) hidePanel(); else showPanel();
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
        if (ok && wantPanelAfterPerm) showPanel();
        if (ok && wantMeterAfterPerm) startMeter();
        wantPanelAfterPerm = false;
        wantMeterAfterPerm = false;
    }

    @Override
    public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) hidePanel();
        else if (drawer.getVisibility() == View.VISIBLE) toggleDrawer();
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
