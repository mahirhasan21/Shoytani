package com.ariyan.shoytani;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tiny Chrome-extension loader for WebView.
 * Supports: .zip / .crx (manifest.json + content_scripts js/css + popup) and .user.js userscripts.
 * Content scripts are injected into the page; chrome.storage / chrome.runtime are lightweight shims.
 */
public class Extensions {
    public static final String HOST = "appassets.androidplatform.net";
    private static final long MAX_BYTES = 60L * 1024 * 1024;

    public static class Ext {
        public String id, name, version = "1.0", desc = "", icon = "", popup = "", runAt = "document_idle";
        public boolean enabled = true;
        public final List<String> js = new ArrayList<>(), css = new ArrayList<>();
        public final List<String> matches = new ArrayList<>(), excludes = new ArrayList<>();
        final List<Pattern> pats = new ArrayList<>(), exPats = new ArrayList<>();
        public File dir;
    }

    private final File root;
    private final SharedPreferences prefs;
    private List<Ext> list = new ArrayList<>();

    public Extensions(Context c) {
        root = new File(c.getFilesDir(), "extensions");
        root.mkdirs();
        prefs = c.getSharedPreferences("ext", Context.MODE_PRIVATE);
        reload();
    }

    public File rootDir() { return root; }
    public synchronized List<Ext> all() { return new ArrayList<>(list); }

    public synchronized Ext get(String id) {
        for (Ext e : list) if (e.id.equals(id)) return e;
        return null;
    }

    public synchronized void reload() {
        List<Ext> n = new ArrayList<>();
        File[] ds = root.listFiles();
        if (ds != null) for (File d : ds) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            try { Ext e = parse(d); if (e != null) n.add(e); } catch (Exception ignored) { }
        }
        Collections.sort(n, new Comparator<Ext>() {
            @Override public int compare(Ext a, Ext b) { return a.name.compareToIgnoreCase(b.name); }
        });
        list = n;
    }

    public void setEnabled(String id, boolean on) {
        prefs.edit().putBoolean("on_" + id, on).apply();
        Ext e = get(id);
        if (e != null) e.enabled = on;
    }

    public synchronized boolean remove(String id) {
        Ext e = get(id);
        if (e == null) return false;
        deleteRec(e.dir);
        prefs.edit().remove("on_" + id).apply();
        reload();
        return true;
    }

    // ---------------------------------------------------------------- install
    /** Installs from raw file bytes. Returns the installed extension. Throws with a readable message on failure. */
    public synchronized Ext install(InputStream in, String fileName) throws Exception {
        byte[] data = readAll(in);
        String lower = fileName == null ? "" : fileName.toLowerCase();
        File tmp = new File(root, ".tmp_" + System.nanoTime());
        tmp.mkdirs();
        try {
            if (data.length > 4 && data[0] == 'C' && data[1] == 'r' && data[2] == '2' && data[3] == '4') {
                unzip(crxToZip(data), tmp);
            } else if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
                unzip(data, tmp);
            } else if (lower.endsWith(".js")) {
                writeUserScript(new String(data, StandardCharsets.UTF_8), fileName, tmp);
            } else {
                throw new Exception("এই ফাইল সাপোর্টেড না (.zip / .crx / .user.js দাও)");
            }
            Ext e = parse(tmp);
            if (e == null) throw new Exception("manifest.json পাওয়া যায়নি");
            String id = makeId(e.name);
            File dest = new File(root, id);
            if (dest.exists()) deleteRec(dest);
            if (!tmp.renameTo(dest)) throw new Exception("সেভ করা যায়নি");
            reload();
            Ext done = get(id);
            if (done == null) throw new Exception("লোড করা যায়নি");
            setEnabled(id, true);
            return done;
        } finally {
            if (tmp.exists()) deleteRec(tmp);
        }
    }

    private static String makeId(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "ext";
        if (slug.length() > 24) slug = slug.substring(0, 24);
        return slug + "_" + Integer.toHexString(name.hashCode());
    }

    private static byte[] crxToZip(byte[] d) throws Exception {
        int ver = le(d, 4);
        int start;
        if (ver == 2) start = 16 + le(d, 8) + le(d, 12);
        else if (ver == 3) start = 12 + le(d, 8);
        else throw new Exception("CRX ভার্সন সাপোর্টেড না");
        if (start <= 0 || start >= d.length) throw new Exception("CRX ফাইল নষ্ট");
        byte[] z = new byte[d.length - start];
        System.arraycopy(d, start, z, 0, z.length);
        return z;
    }

    private static int le(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }

    private static void unzip(byte[] zip, File dest) throws Exception {
        // find a common single top-level folder that holds manifest.json
        String prefix = "";
        boolean hasRootManifest = false;
        String nested = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry en;
            while ((en = z.getNextEntry()) != null) {
                String n = en.getName().replace('\\', '/');
                if (n.equals("manifest.json")) hasRootManifest = true;
                else if (n.endsWith("/manifest.json") && n.indexOf('/') == n.lastIndexOf('/') && nested == null)
                    nested = n.substring(0, n.indexOf('/') + 1);
            }
        }
        if (!hasRootManifest && nested != null) prefix = nested;
        String canon = dest.getCanonicalPath() + File.separator;
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry en;
            while ((en = z.getNextEntry()) != null) {
                String n = en.getName().replace('\\', '/');
                if (!n.startsWith(prefix)) continue;
                n = n.substring(prefix.length());
                if (n.isEmpty()) continue;
                File f = new File(dest, n);
                if (!f.getCanonicalPath().startsWith(canon)) continue; // zip-slip guard
                if (en.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = z.read(buf)) > 0) {
                        total += r;
                        if (total > MAX_BYTES) throw new Exception("ফাইল অনেক বড়");
                        o.write(buf, 0, r);
                    }
                }
            }
        }
    }

    private static void writeUserScript(String src, String fileName, File dest) throws Exception {
        String name = fileName == null ? "Userscript" : fileName.replaceAll("(?i)\\.user\\.js$|(?i)\\.js$", "");
        String version = "1.0", desc = "Userscript", runAt = "document_idle";
        JSONArray matches = new JSONArray();
        for (String line : src.split("\n")) {
            line = line.trim();
            if (!line.startsWith("//")) continue;
            java.util.regex.Matcher m = Pattern.compile("^//\\s*@(\\S+)\\s+(.*)$").matcher(line);
            if (!m.find()) continue;
            String k = m.group(1), v = m.group(2).trim();
            if (k.equals("name") && !v.isEmpty()) name = v;
            else if (k.equals("version")) version = v;
            else if (k.equals("description")) desc = v;
            else if (k.equals("run-at")) runAt = v.replace('-', '_');
            else if (k.equals("match") || k.equals("include")) matches.put(v.startsWith("/") ? "<all_urls>" : v);
        }
        if (matches.length() == 0) matches.put("<all_urls>");
        File js = new File(dest, "script.user.js");
        try (FileOutputStream o = new FileOutputStream(js)) { o.write(src.getBytes(StandardCharsets.UTF_8)); }
        JSONObject man = new JSONObject();
        man.put("manifest_version", 3).put("name", name).put("version", version).put("description", desc);
        JSONObject cs = new JSONObject();
        cs.put("matches", matches).put("js", new JSONArray().put("script.user.js")).put("run_at", runAt);
        man.put("content_scripts", new JSONArray().put(cs));
        try (FileOutputStream o = new FileOutputStream(new File(dest, "manifest.json"))) {
            o.write(man.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------------- parse
    private Ext parse(File dir) throws Exception {
        File mf = new File(dir, "manifest.json");
        if (!mf.exists()) return null;
        String txt = new String(readAll(new java.io.FileInputStream(mf)), StandardCharsets.UTF_8);
        if (txt.startsWith("﻿")) txt = txt.substring(1);
        JSONObject m = new JSONObject(txt);
        Ext e = new Ext();
        e.dir = dir;
        e.name = resolveMsg(dir, m, m.optString("name", dir.getName()));
        e.version = m.optString("version", "1.0");
        e.desc = resolveMsg(dir, m, m.optString("description", ""));
        e.id = dir.getName().startsWith(".tmp_") ? makeId(e.name) : dir.getName();
        e.enabled = prefs.getBoolean("on_" + e.id, true);

        JSONObject icons = m.optJSONObject("icons");
        if (icons != null) {
            int best = -1;
            JSONArray ks = icons.names();
            if (ks != null) for (int i = 0; i < ks.length(); i++) {
                String k = ks.getString(i);
                int sz = 0;
                try { sz = Integer.parseInt(k); } catch (Exception ignored) { }
                if (sz > best) { best = sz; e.icon = icons.optString(k); }
            }
        }
        JSONObject act = m.optJSONObject("action");
        if (act == null) act = m.optJSONObject("browser_action");
        if (act != null) e.popup = act.optString("default_popup", "");

        JSONArray css = m.optJSONArray("content_scripts");
        if (css != null) for (int i = 0; i < css.length(); i++) {
            JSONObject c = css.getJSONObject(i);
            JSONArray ma = c.optJSONArray("matches");
            if (ma != null) for (int k = 0; k < ma.length(); k++) {
                String p = ma.getString(k);
                e.matches.add(p);
                Pattern pp = toPattern(p);
                if (pp != null) e.pats.add(pp);
            }
            JSONArray ex = c.optJSONArray("exclude_matches");
            if (ex != null) for (int k = 0; k < ex.length(); k++) {
                Pattern pp = toPattern(ex.getString(k));
                if (pp != null) e.exPats.add(pp);
            }
            JSONArray js = c.optJSONArray("js");
            if (js != null) for (int k = 0; k < js.length(); k++) if (!e.js.contains(js.getString(k))) e.js.add(js.getString(k));
            JSONArray cc = c.optJSONArray("css");
            if (cc != null) for (int k = 0; k < cc.length(); k++) if (!e.css.contains(cc.getString(k))) e.css.add(cc.getString(k));
            String ra = c.optString("run_at", "");
            if (ra.equals("document_start")) e.runAt = ra;
        }
        return e;
    }

    private static String resolveMsg(File dir, JSONObject m, String v) {
        if (v == null || !v.startsWith("__MSG_") || !v.endsWith("__")) return v;
        String key = v.substring(6, v.length() - 2);
        String[] locs = {m.optString("default_locale", "en"), "en", "en_US"};
        for (String l : locs) {
            try {
                File f = new File(dir, "_locales/" + l + "/messages.json");
                if (!f.exists()) continue;
                JSONObject j = new JSONObject(new String(readAll(new java.io.FileInputStream(f)), StandardCharsets.UTF_8));
                for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (k.equalsIgnoreCase(key)) return j.getJSONObject(k).optString("message", v);
                }
            } catch (Exception ignored) { }
        }
        return v;
    }

    // ---------------------------------------------------------------- matching
    static Pattern toPattern(String p) {
        try {
            if (p.equals("<all_urls>")) return Pattern.compile("^(https?|file)://.*$");
            java.util.regex.Matcher m = Pattern.compile("^(\\*|https?|file)://([^/]*)(/.*)$").matcher(p);
            if (!m.find()) return null;
            String scheme = m.group(1), host = m.group(2), path = m.group(3);
            String s = scheme.equals("*") ? "https?" : Pattern.quote(scheme);
            String h;
            if (host.equals("*")) h = "[^/]*";
            else if (host.startsWith("*.")) h = "([^/]+\\.)?" + Pattern.quote(host.substring(2));
            else h = Pattern.quote(host);
            StringBuilder pr = new StringBuilder();
            String[] parts = path.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) pr.append(".*");
                if (!parts[i].isEmpty()) pr.append(Pattern.quote(parts[i]));
            }
            return Pattern.compile("^" + s + "://" + h + (host.contains(":") ? "" : "(:\\d+)?") + pr + "$");
        } catch (Exception e) {
            return null;
        }
    }

    public boolean matches(Ext e, String url) {
        if (url == null || e.pats.isEmpty()) return false;
        for (Pattern x : e.exPats) if (x.matcher(url).matches()) return false;
        for (Pattern x : e.pats) if (x.matcher(url).matches()) return true;
        return false;
    }

    // ---------------------------------------------------------------- injection
    private static final String SHIM =
            "var __id=%ID%;var __K='__dm_ext_'+__id;"
            + "function __ld(){try{return JSON.parse(localStorage.getItem(__K)||'{}')}catch(e){return {}}}"
            + "function __sv(o){try{localStorage.setItem(__K,JSON.stringify(o))}catch(e){}}"
            + "function __area(){return {"
            + "get:function(k,cb){var d=__ld(),r={};if(k==null){r=d}else if(typeof k==='string'){if(k in d)r[k]=d[k]}"
            + "else if(Array.isArray(k)){k.forEach(function(x){if(x in d)r[x]=d[x]})}else{for(var x in k){r[x]=(x in d)?d[x]:k[x]}}"
            + "if(cb){setTimeout(function(){cb(r)},0);return}return Promise.resolve(r)},"
            + "set:function(o,cb){var d=__ld();for(var x in o)d[x]=o[x];__sv(d);if(cb){setTimeout(cb,0);return}return Promise.resolve()},"
            + "remove:function(k,cb){var d=__ld();[].concat(k).forEach(function(x){delete d[x]});__sv(d);if(cb){setTimeout(cb,0);return}return Promise.resolve()},"
            + "clear:function(cb){__sv({});if(cb){setTimeout(cb,0);return}return Promise.resolve()}}}"
            + "var __ev=function(){return {addListener:function(){},removeListener:function(){},hasListener:function(){return false}}};"
            + "var chrome={runtime:{id:__id,getURL:function(p){return 'https://" + HOST + "/ext/'+__id+'/'+String(p).replace(/^\\//,'')},"
            + "sendMessage:function(){var cb=arguments[arguments.length-1];if(typeof cb==='function')setTimeout(function(){cb()},0);return Promise.resolve()},"
            + "onMessage:__ev(),onInstalled:__ev(),connect:function(){return {postMessage:function(){},onMessage:__ev(),onDisconnect:__ev(),disconnect:function(){}}},"
            + "getManifest:function(){return {}},lastError:undefined},"
            + "storage:{local:__area(),sync:__area(),session:__area(),onChanged:__ev()},"
            + "i18n:{getMessage:function(k){return k},getUILanguage:function(){return navigator.language}},"
            + "extension:{getURL:function(p){return chrome.runtime.getURL(p)}},"
            + "tabs:{query:function(q,cb){var r=[{id:1,url:location.href,active:true}];if(cb){cb(r);return}return Promise.resolve(r)},sendMessage:function(){}},"
            + "action:{setBadgeText:function(){},setBadgeBackgroundColor:function(){},setIcon:function(){}},"
            + "browserAction:{setBadgeText:function(){},setBadgeBackgroundColor:function(){}}};"
            + "var browser=chrome;";

    /** Shim only (used for popup pages). */
    public String shimFor(Ext e) {
        return "(function(){try{" + SHIM.replace("%ID%", JSONObject.quote(e.id))
                + "window.chrome=chrome;window.browser=chrome;}catch(x){}})();";
    }

    /** Full content-script bundle for one extension. */
    public String bundleFor(Ext e) {
        StringBuilder sb = new StringBuilder();
        String flag = "__dm_loaded_" + e.id.replaceAll("[^A-Za-z0-9_]", "_");
        sb.append("(function(){try{if(window.").append(flag).append(")return;window.").append(flag).append("=1;");
        sb.append(SHIM.replace("%ID%", JSONObject.quote(e.id)));
        for (String c : e.css) {
            String t = readText(new File(e.dir, c));
            if (t != null) sb.append("try{var __s=document.createElement('style');__s.textContent=")
                    .append(JSONObject.quote(t))
                    .append(";(document.head||document.documentElement).appendChild(__s)}catch(_){}");
        }
        for (String j : e.js) {
            String t = readText(new File(e.dir, j));
            if (t != null) sb.append("\ntry{\n").append(t).append("\n}catch(__e){console.error('[").append(e.name.replace("'", "")).append("]',__e)}\n");
        }
        sb.append("}catch(__x){}})();");
        return sb.toString();
    }

    // ---------------------------------------------------------------- utils
    private static String readText(File f) {
        try { return new String(readAll(new java.io.FileInputStream(f)), StandardCharsets.UTF_8); }
        catch (Exception e) { return null; }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int r;
            long t = 0;
            while ((r = i.read(b)) > 0) {
                t += r;
                if (t > MAX_BYTES) throw new Exception("ফাইল অনেক বড়");
                o.write(b, 0, r);
            }
            return o.toByteArray();
        }
    }

    private static void deleteRec(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteRec(x);
        f.delete();
    }

    public static String hostOf(String url) {
        try { return new URI(url).getHost(); } catch (Exception e) { return null; }
    }
}
