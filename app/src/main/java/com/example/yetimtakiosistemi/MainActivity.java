package com.example.yetimtakiosistemi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import java.util.HashMap;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.widget.FrameLayout;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final boolean LOCAL = false;
    private static final int FILE_REQ = 1001;
    private static final int BRIDGE_REQ = 1002;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean pageReady = false;
    private ArrayList<Uri> pendingUris = null;
    private String pendingText = null;
    private String shimJs = "";
    private static final int PERM_REQ = 1003;
    private static final int NOTIF_REQ = 1005;
    private final HashMap<String, FileOutputStream> saveOut = new HashMap<String, FileOutputStream>();
    private final HashMap<String, String[]> saveMeta = new HashMap<String, String[]>();
    private PermissionRequest pendingPerm = null;
    private GeolocationPermissions.Callback pendingGeoCb = null;
    private String pendingGeoOrigin = null;
    private View customView = null;
    private WebChromeClient.CustomViewCallback customCb = null;

    private Intent buildPicker(String[] accept, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        ArrayList<String> mimes = new ArrayList<String>();
        boolean any = false;
        if (accept != null) {
            for (String a : accept) {
                if (a == null) continue;
                a = a.trim();
                if (a.length() == 0) { any = true; continue; }
                if (a.indexOf('/') > 0) mimes.add(a);
            }
        }
        if (!any && mimes.size() > 0) {
            if (mimes.size() == 1) i.setType(mimes.get(0));
            else i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return Intent.createChooser(i, null);
    }

    private void toast(final String m) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); }
        });
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() { web.evaluateJavascript(code, null); }
        });
    }

    public class Bridge {
        @JavascriptInterface
        public void pick(final String accept, final boolean multiple) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        String[] acc = (accept == null || accept.length() == 0) ? new String[0] : accept.split(",");
                        startActivityForResult(buildPicker(acc, multiple), BRIDGE_REQ);
                    } catch (Exception e) {
                        toast("Dosya secici acilamadi: " + e);
                        js("window.__nativeDone()");
                    }
                }
            });
        }

        @JavascriptInterface
        public void share(String title, String text, String url, String filesJson) {
            doShare(title, text, url, filesJson);
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        PrintDocumentAdapter adapter = web.createPrintDocumentAdapter("Belge");
                        pm.print("Belge", adapter, new PrintAttributes.Builder().build());
                    } catch (Exception e) {
                        toast("Yazdirma acilamadi: " + e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("text", text));
                    } catch (Exception e) {
                        toast("Kopyalanamadi: " + e);
                    }
                }
            });
        }

        @JavascriptInterface
        public String paste() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                    return t == null ? "" : t.toString();
                }
            } catch (Exception e) { }
            return "";
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public String notifPerm() {
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED ? "granted" : "default";
            }
            return notificationsEnabled() ? "granted" : "denied";
        }

        @JavascriptInterface
        public void notifRequest() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                            js("window.__notifResolve && window.__notifResolve('granted')");
                        } else {
                            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, NOTIF_REQ);
                        }
                    } else {
                        js("window.__notifResolve && window.__notifResolve('" + (notificationsEnabled() ? "granted" : "denied") + "')");
                    }
                }
            });
        }

        @JavascriptInterface
        public void showNote(String title, String body, String tag) {
            showNotification(title, body, tag);
        }

        @JavascriptInterface
        public void beginSave(String id, String name, String mime) {
            try {
                File dir = new File(getCacheDir(), "dl");
                dir.mkdirs();
                saveOut.put(id, new FileOutputStream(new File(dir, id + ".tmp")));
                saveMeta.put(id, new String[] { name, mime });
            } catch (Exception e) {
                toast("Kaydedilemedi: " + e);
            }
        }

        @JavascriptInterface
        public void chunkSave(String id, String b64) {
            try {
                FileOutputStream fo = saveOut.get(id);
                if (fo != null) fo.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                toast("Kaydedilemedi: " + e);
            }
        }

        @JavascriptInterface
        public void endSave(String id) {
            try {
                FileOutputStream fo = saveOut.remove(id);
                String[] meta = saveMeta.remove(id);
                if (fo == null || meta == null) return;
                fo.close();
                finishDownload(new File(new File(getCacheDir(), "dl"), id + ".tmp"), meta[0], meta[1]);
            } catch (Exception e) {
                toast("Kaydedilemedi: " + e);
            }
        }

        @JavascriptInterface
        public void saveBlob(String name, String mime, String b64) {
            try {
                saveDownload(name, mime, Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                toast("Kaydedilemedi: " + e);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (LOCAL) {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        if (LOCAL) shimJs = readAsset("_apkshim.js");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("intent:")
                        || (LOCAL && (url.startsWith("http://") || url.startsWith("https://")))) {
                    openExternal(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (LOCAL && shimJs.length() > 0) view.evaluateJavascript(shimJs, null);
                pageReady = true;
                flushPending();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    boolean multi = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                    startActivityForResult(buildPicker(params.getAcceptTypes(), multi), FILE_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("Dosya secici acilamadi: " + e);
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        ArrayList<String> need = new ArrayList<String>();
                        for (String r : req.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) need.add(Manifest.permission.CAMERA);
                            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) need.add(Manifest.permission.RECORD_AUDIO);
                        }
                        ArrayList<String> missing = missingPerms(need);
                        if (missing.isEmpty()) {
                            req.grant(req.getResources());
                            return;
                        }
                        if (pendingPerm != null) pendingPerm.deny();
                        pendingPerm = req;
                        requestPermissions(missing.toArray(new String[0]), PERM_REQ);
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                ArrayList<String> need = new ArrayList<String>();
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
                ArrayList<String> missing = missingPerms(need);
                if (missing.isEmpty()) {
                    cb.invoke(origin, true, false);
                    return;
                }
                pendingGeoCb = cb;
                pendingGeoOrigin = origin;
                requestPermissions(missing.toArray(new String[0]), PERM_REQ + 1);
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = cb;
                ((ViewGroup) getWindow().getDecorView()).addView(view,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                if (LOCAL && (url.startsWith("blob:") || url.startsWith("data:"))) fetchAndSave(url, cd, mime);
                else openExternal(url);
            }
        });
        if (LOCAL) web.addJavascriptInterface(new Bridge(), "AndroidFiles");
        web.loadUrl("https://yetim-takio-sistemi.lovable.app");
        handleIntent(getIntent());
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) { }
    }

    private void fetchAndSave(String url, String cd, String mime) {
        String name = URLUtil.guessFileName(url.startsWith("data:") ? "download" : url, cd, mime);
        js("(function(){fetch(" + JSONObject.quote(url) + ").then(function(r){return r.blob();}).then(function(b){"
                + "if(window.__apkSaveBlob)window.__apkSaveBlob(b," + JSONObject.quote(name) + ");});})()");
    }

    private void saveDownload(String name, String mime, byte[] data) {
        try {
            File dir = new File(getCacheDir(), "dl");
            dir.mkdirs();
            File f = new File(dir, "t" + System.currentTimeMillis() + ".tmp");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
            finishDownload(f, name, mime);
        } catch (Exception e) {
            toast("Kaydedilemedi: " + e);
        }
    }

    private void copyFile(File src, OutputStream os) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
    }

    private void finishDownload(File src, String name, String mime) {
        try {
            if (name == null || name.length() == 0) name = "dosya";
            if (mime == null || mime.length() == 0) mime = "application/octet-stream";
            Uri opened = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                copyFile(src, getContentResolver().openOutputStream(u));
                opened = u;
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                copyFile(src, new FileOutputStream(new File(dir, name)));
            }
            src.delete();
            showSaved(name, mime, opened);
        } catch (Exception e) {
            toast("Kaydedilemedi: " + e);
        }
    }

    private void showSaved(final String name, final String mime, final Uri uri) {
        runOnUiThread(new Runnable() {
            public void run() {
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this);
                b.setTitle("Indirildi");
                b.setMessage(name + " dosyasi Indirilenler klasorune kaydedildi.");
                b.setNegativeButton("Tamam", null);
                if (uri != null) {
                    b.setPositiveButton("Ac", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            try {
                                Intent it = new Intent(Intent.ACTION_VIEW);
                                it.setDataAndType(uri, mime);
                                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(it);
                            } catch (Exception e) {
                                toast("Acilamadi: " + e);
                            }
                        }
                    });
                }
                b.show();
            }
        });
    }

    private void doShare(String title, String text, String url, String filesJson) {
        try {
            JSONArray arr = new JSONArray(filesJson);
            final ArrayList<Uri> uris = new ArrayList<Uri>();
            String mime = null;
            boolean mixed = false;
            File dir = new File(getCacheDir(), "share");
            dir.mkdirs();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String n = new File(o.getString("n")).getName();
                if (n.length() == 0) n = "file" + i;
                FileOutputStream fo = new FileOutputStream(new File(dir, n));
                fo.write(Base64.decode(o.getString("b"), Base64.DEFAULT));
                fo.close();
                uris.add(Uri.parse("content://" + getPackageName() + ".shareprovider/" + Uri.encode(n)));
                String m = o.optString("m", "application/octet-stream");
                if (mime == null) mime = m; else if (!mime.equals(m)) mixed = true;
            }
            final Intent it = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
            String body = text == null ? "" : text;
            if (url != null && url.length() > 0) body = body.length() > 0 ? body + String.valueOf((char) 10) + url : url;
            if (uris.isEmpty()) {
                it.setType("text/plain");
            } else {
                it.setType((mixed || mime == null) ? "*/*" : mime);
                if (uris.size() > 1) it.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                else it.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                ClipData cd = ClipData.newRawUri(null, uris.get(0));
                for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
                it.setClipData(cd);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (body.length() > 0) it.putExtra(Intent.EXTRA_TEXT, body);
            if (title != null && title.length() > 0) it.putExtra(Intent.EXTRA_SUBJECT, title);
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(Intent.createChooser(it, null));
                    } catch (Exception e) {
                        toast("Paylasilamadi: " + e);
                    }
                }
            });
        } catch (Exception e) {
            toast("Paylasilamadi: " + e);
        }
    }

    private void handleIntent(Intent it) {
        if (it == null) return;
        String act = it.getAction();
        ArrayList<Uri> uris = new ArrayList<Uri>();
        String text = null;
        if (Intent.ACTION_VIEW.equals(act) && it.getData() != null) {
            uris.add(it.getData());
        } else if (Intent.ACTION_SEND.equals(act)) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u); else text = it.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
            ArrayList<Uri> l = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else {
            return;
        }
        it.setAction(null);
        if (uris.isEmpty() && text == null) return;
        pendingUris = uris;
        pendingText = text;
        if (pageReady) flushPending();
    }

    private void flushPending() {
        final ArrayList<Uri> u = pendingUris;
        final String t = pendingText;
        pendingUris = null;
        pendingText = null;
        if (t != null) js("window.__incomingText && window.__incomingText(" + JSONObject.quote(t) + ")");
        if (u != null && !u.isEmpty()) {
            new Thread(new Runnable() {
                public void run() { sendFiles(u, "__incomingFile", "__incomingDone"); }
            }).start();
        }
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleIntent(it);
    }

    private void sendFiles(ArrayList<Uri> uris, String fileFn, String doneFn) {
        for (Uri u : uris) {
            try {
                String name = "dosya";
                Cursor c = getContentResolver().query(u, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (ix >= 0) name = c.getString(ix);
                        }
                    } finally { c.close(); }
                }
                String mime = getContentResolver().getType(u);
                if (mime == null) mime = "";
                InputStream in = getContentResolver().openInputStream(u);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                js("window." + fileFn + "(" + JSONObject.quote(name) + "," + JSONObject.quote(mime) + ",'" + b64 + "')");
            } catch (Exception e) {
                toast("Dosya okunamadi: " + e);
            }
        }
        js("window." + doneFn + "()");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQ && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        if (requestCode == BRIDGE_REQ) {
            final ArrayList<Uri> uris = new ArrayList<Uri>();
            if (resultCode == RESULT_OK && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null) {
                    for (int k = 0; k < cd.getItemCount(); k++) uris.add(cd.getItemAt(k).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            new Thread(new Runnable() {
                public void run() { sendFiles(uris, "__nativeFile", "__nativeDone"); }
            }).start();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private boolean notificationsEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return Build.VERSION.SDK_INT >= 24 ? nm.areNotificationsEnabled() : true;
    }

    private void showNotification(String title, String body, String tag) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("apk_default", "Bildirimler", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(this, "apk_default");
            } else {
                b = new Notification.Builder(this);
            }
            Intent in = new Intent(this, MainActivity.class);
            in.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, in, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            b.setSmallIcon(android.R.drawable.ic_dialog_info);
            b.setContentTitle(title);
            b.setContentText(body);
            b.setAutoCancel(true);
            b.setContentIntent(pi);
            int id = (tag != null && tag.length() > 0) ? tag.hashCode() : (int) (System.currentTimeMillis() & 0x7fffffff);
            nm.notify(id, b.build());
        } catch (Exception e) {
            toast("Bildirim gosterilemedi: " + e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private ArrayList<String> missingPerms(ArrayList<String> need) {
        ArrayList<String> m = new ArrayList<String>();
        if (Build.VERSION.SDK_INT < 23) return m;
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) m.add(p);
        }
        return m;
    }

    private void hideCustom() {
        if (customView == null) return;
        ((ViewGroup) getWindow().getDecorView()).removeView(customView);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean ok = grantResults.length > 0;
        for (int g : grantResults) {
            if (g != PackageManager.PERMISSION_GRANTED) ok = false;
        }
        if (requestCode == PERM_REQ && pendingPerm != null) {
            if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
            pendingPerm = null;
        } else if (requestCode == PERM_REQ + 1 && pendingGeoCb != null) {
            pendingGeoCb.invoke(pendingGeoOrigin, ok, false);
            pendingGeoCb = null;
        } else if (requestCode == NOTIF_REQ) {
            js("window.__notifResolve && window.__notifResolve('" + (ok ? "granted" : "denied") + "')");
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustom();
            return;
        }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
