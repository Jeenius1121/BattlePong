package com.battlepong.jeu;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/* Battle Pong en application : le jeu est rangé dans l'application (dossier assets/jeu) et servi
   à la page sous l'adresse https://jeu.local/ ; le jeu propose les mises à jour (nouvel APK). */
public class MainActivity extends Activity {
    private WebView vue;
    long telechargement = -1;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        vue = new WebView(this);
        WebSettings r = vue.getSettings();
        r.setJavaScriptEnabled(true);
        r.setDomStorageEnabled(true);
        r.setMediaPlaybackRequiresUserGesture(false);
        r.setAllowFileAccess(false);
        vue.setWebViewClient(new Client(this));
        vue.addJavascriptInterface(new Pont(this), "BattlePongApp");
        setContentView(vue);
        pleinEcran();
        if (etat != null) vue.restoreState(etat); else vue.loadUrl("https://jeu.local/index.html");
    }

    private static String type(String c) {
        if (c.endsWith(".html")) return "text/html";
        if (c.endsWith(".json")) return "application/json";
        if (c.endsWith(".js")) return "application/javascript";
        if (c.endsWith(".css")) return "text/css";
        if (c.endsWith(".png")) return "image/png";
        if (c.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void pleinEcran() {
        vue.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) pleinEcran(); }
    @Override protected void onSaveInstanceState(Bundle e) { super.onSaveInstanceState(e); vue.saveState(e); }
    @Override protected void onPause() { super.onPause(); vue.onPause(); }
    @Override protected void onResume() { super.onResume(); vue.onResume(); }
    @Override public void onBackPressed() { if (vue.canGoBack()) vue.goBack(); else super.onBackPressed(); }

    /* sert les fichiers du jeu rangés dans l'application */
    private static class Client extends WebViewClient {
        private final MainActivity a;
        Client(MainActivity x) { a = x; }
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!"jeu.local".equals(u.getHost())) return null;
            String chemin = u.getPath() == null || u.getPath().equals("/") ? "/index.html" : u.getPath();
            try {
                InputStream in = a.getAssets().open("jeu" + chemin);
                return new WebResourceResponse(type(chemin), "utf-8", in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
            }
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if ("jeu.local".equals(u.getHost())) return false;
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) {}
            return true;
        }
    }

    /* ce que la page du jeu peut demander à l'application */
    private static class Pont {
        private final MainActivity a;
        Pont(MainActivity x) { a = x; }
        @JavascriptInterface public String version() {
            try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public void mettreAJour(final String url) {
            a.runOnUiThread(new Lancer(a, url));
        }
    }

    private static class Lancer implements Runnable {
        private final MainActivity a; private final String url;
        Lancer(MainActivity x, String u) { a = x; url = u; }
        @Override public void run() { a.telecharger(url); }
    }

    private static class Fin extends BroadcastReceiver {
        private final MainActivity a; private final DownloadManager dm;
        Fin(MainActivity x, DownloadManager d) { a = x; dm = d; }
        @Override public void onReceive(Context c, Intent i) {
            if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != a.telechargement) return;
            try { a.unregisterReceiver(this); } catch (Exception e) {}
            a.installer(dm, a.telechargement);
        }
    }

    /* télécharge le nouvel APK puis lance son installation (par-dessus l'ancienne version) */
    void telecharger(String url) {
        try {
            File ancien = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "battlepong.apk");
            if (ancien.exists()) ancien.delete();
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Battle Pong");
            req.setDescription("Mise à jour du jeu");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "battlepong.apk");
            telechargement = dm.enqueue(req);
            BroadcastReceiver fin = new Fin(this, dm);
            IntentFilter filtre = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(fin, filtre, Context.RECEIVER_EXPORTED);
            else registerReceiver(fin, filtre);
            Toast.makeText(this, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); // en dernier recours : le navigateur
        }
    }

    void installer(DownloadManager dm, long id) {
        Uri fichier = dm.getUriForDownloadedFile(id);
        if (fichier == null) { Toast.makeText(this, "Le téléchargement a échoué", Toast.LENGTH_LONG).show(); return; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(fichier, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
