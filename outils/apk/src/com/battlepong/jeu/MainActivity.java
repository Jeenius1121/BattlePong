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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/* Battle Pong en application : le jeu est rangé dans l'application (dossier assets/jeu) et servi à la page
   sous l'adresse https://jeu.local/.
   Mise à jour du jeu sans réinstaller : quand la page voit une nouvelle version en ligne, le bouton « Mettre à jour »
   demande à l'application de télécharger, depuis le dépôt GitHub, seulement les fichiers du jeu qui ont changé
   (comparés avec leur empreinte git). Ils sont rangés dans le stockage de l'application (dossier « jeu ») et servis
   à la place de ceux de l'APK. Un nouvel APK (rarement utile) reste possible : la page le propose si besoin. */
public class MainActivity extends Activity {
    static final String DEPOT = "Jeenius1121/BattlePong";
    private WebView vue;
    long telechargement = -1;
    volatile boolean majEnCours = false;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        nettoyerSiPerime();
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
        if (c.endsWith(".jpg") || c.endsWith(".jpeg")) return "image/jpeg";
        if (c.endsWith(".webp")) return "image/webp";
        if (c.endsWith(".svg")) return "image/svg+xml";
        if (c.endsWith(".mp3")) return "audio/mpeg";
        if (c.endsWith(".wav")) return "audio/wav";
        if (c.endsWith(".ogg")) return "audio/ogg";
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

    /* ===== fichiers du jeu : ceux téléchargés (dossier « jeu » du stockage) passent avant ceux de l'APK ===== */
    File dossierJeu() { return new File(getFilesDir(), "jeu"); }

    // un fichier du jeu : téléchargé s'il existe, sinon celui rangé dans l'APK (null s'il n'existe nulle part)
    InputStream ouvrir(String chemin) throws IOException {
        File f = new File(dossierJeu(), chemin);
        if (f.isFile()) return new FileInputStream(f);
        try { return getAssets().open("jeu/" + chemin); } catch (IOException e) { return null; }
    }

    static boolean cheminSur(String c) { return c.length() > 0 && !c.contains("..") && !c.startsWith("/") && !c.contains("\\"); }

    static String lireTout(InputStream in) throws IOException { return new String(octets(in), "UTF-8"); }

    static byte[] octets(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    static String versionDe(InputStream in) {
        if (in == null) return "";
        try { return new JSONObject(lireTout(in)).optString("version", ""); } catch (Exception e) { return ""; }
    }

    String versionApk() { try { return versionDe(getAssets().open("jeu/version.json")); } catch (IOException e) { return ""; } }
    String versionTelechargee() { File f = new File(dossierJeu(), "version.json"); try { return f.isFile() ? versionDe(new FileInputStream(f)) : ""; } catch (IOException e) { return ""; } }
    String versionJeu() { try { return versionDe(ouvrir("version.json")); } catch (IOException e) { return ""; } }

    // a est-elle une version plus récente que b ? (1.65.0 > 1.64.2)
    static boolean plusRecente(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int p = i < x.length ? entier(x[i]) : 0, q = i < y.length ? entier(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }
    static int entier(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    // un nouvel APK a été installé avec un jeu plus récent (ou égal) que celui téléchargé : on oublie les fichiers téléchargés
    void nettoyerSiPerime() {
        File d = dossierJeu();
        if (!d.exists()) return;
        String t = versionTelechargee(), a = versionApk();
        if (t.isEmpty() || !plusRecente(t, a)) supprimer(d);
        supprimer(new File(getFilesDir(), "jeu-maj")); // reste d'une mise à jour interrompue
    }

    static void supprimer(File f) {
        if (f.isDirectory()) { File[] l = f.listFiles(); if (l != null) for (File x : l) supprimer(x); }
        if (f.exists()) f.delete();
    }

    /* sert les fichiers du jeu à la page */
    private static class Client extends WebViewClient {
        private final MainActivity a;
        Client(MainActivity x) { a = x; }
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!"jeu.local".equals(u.getHost())) return null;
            String chemin = u.getPath() == null || u.getPath().equals("/") ? "index.html" : u.getPath().substring(1);
            try {
                InputStream in = cheminSur(chemin) ? a.ouvrir(chemin) : null;
                if (in != null) return new WebResourceResponse(type(chemin), "utf-8", in);
            } catch (IOException e) { }
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if ("jeu.local".equals(u.getHost())) return false;
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
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
        @JavascriptInterface public int versionAppli() {
            try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
        }
        @JavascriptInterface public String versionJeu() { return a.versionJeu(); }
        @JavascriptInterface public boolean majEnCours() { return a.majEnCours; }
        @JavascriptInterface public void mettreAJourJeu() { a.lancerMajJeu(); }
        @JavascriptInterface public void mettreAJour(final String url) { a.runOnUiThread(new Lancer(a, url)); }
    }

    /* ===== mise à jour du jeu sans réinstaller ===== */
    void lancerMajJeu() {
        synchronized (this) { if (majEnCours) return; majEnCours = true; }
        new Thread(new MajJeu(this)).start();
    }

    // les fichiers du dépôt qui font partie du jeu
    static boolean fichierDuJeu(String p) {
        return p.equals("index.html") || p.equals("version.json") || p.equals("stats.html") || p.equals("stats.json") || p.startsWith("perso/");
    }

    // empreinte git d'un fichier (celle que donne GitHub pour chaque fichier du dépôt)
    static String shaGit(byte[] contenu) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(("blob " + contenu.length + "\0").getBytes("UTF-8"));
        md.update(contenu);
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format("%02x", b & 0xff));
        return s.toString();
    }

    String shaLocal(String chemin) {
        try { InputStream in = ouvrir(chemin); return in == null ? "" : shaGit(octets(in)); } catch (Exception e) { return ""; }
    }

    static HttpURLConnection connexion(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "BattlePong-application");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setUseCaches(false);
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code + " : " + url); }
        return c;
    }

    static String lireUrl(String url) throws IOException {
        HttpURLConnection c = connexion(url);
        try { return lireTout(c.getInputStream()); } finally { c.disconnect(); }
    }

    static byte[] telecharger(String url) throws IOException {
        HttpURLConnection c = connexion(url);
        try { return octets(c.getInputStream()); } finally { c.disconnect(); }
    }

    static String encoder(String chemin) throws IOException {
        StringBuilder s = new StringBuilder();
        for (String morceau : chemin.split("/")) { if (s.length() > 0) s.append('/'); s.append(URLEncoder.encode(morceau, "UTF-8").replace("+", "%20")); }
        return s.toString();
    }

    static void ecrire(File f, byte[] contenu) throws IOException {
        File d = f.getParentFile(); if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("dossier impossible : " + d);
        OutputStream o = new FileOutputStream(f);
        try { o.write(contenu); } finally { o.close(); }
    }

    private static class MajJeu implements Runnable {
        private final MainActivity a;
        MajJeu(MainActivity x) { a = x; }
        @Override public void run() {
            File tmp = new File(a.getFilesDir(), "jeu-maj");
            try {
                // la dernière version du dépôt : son commit et la liste de ses fichiers (avec leur empreinte)
                JSONObject c = new JSONObject(lireUrl("https://api.github.com/repos/" + DEPOT + "/branches/main")).getJSONObject("commit");
                String commit = c.getString("sha"), arbre = c.getJSONObject("commit").getJSONObject("tree").getString("sha");
                JSONArray liste = new JSONObject(lireUrl("https://api.github.com/repos/" + DEPOT + "/git/trees/" + arbre + "?recursive=1")).getJSONArray("tree");
                // les fichiers du jeu qui ont changé (ou qui sont nouveaux)
                List<String[]> aFaire = new ArrayList<String[]>();
                for (int i = 0; i < liste.length(); i++) {
                    JSONObject e = liste.getJSONObject(i);
                    String p = e.optString("path", ""), sha = e.optString("sha", "");
                    if (!"blob".equals(e.optString("type")) || !fichierDuJeu(p) || !cheminSur(p)) continue;
                    if (!sha.equals(a.shaLocal(p))) aFaire.add(new String[] { p, sha });
                }
                // téléchargés d'abord à part (le jeu en place ne bouge pas tant que tout n'est pas arrivé et vérifié)
                supprimer(tmp);
                for (int i = 0; i < aFaire.size(); i++) {
                    a.progres(i, aFaire.size());
                    String p = aFaire.get(i)[0];
                    byte[] contenu = telecharger("https://raw.githubusercontent.com/" + DEPOT + "/" + commit + "/" + encoder(p));
                    if (!aFaire.get(i)[1].equals(shaGit(contenu))) throw new IOException("fichier abîmé : " + p);
                    ecrire(new File(tmp, p), contenu);
                }
                a.progres(aFaire.size(), aFaire.size());
                // tout est là : on range les nouveaux fichiers à leur place (version.json en dernier)
                String[] versionJson = null;
                for (String[] x : aFaire) { if (x[0].equals("version.json")) { versionJson = x; continue; } ranger(tmp, x[0]); }
                if (versionJson != null) ranger(tmp, versionJson[0]);
                supprimer(tmp);
                a.fini(a.versionJeu(), aFaire.size());
            } catch (Exception e) {
                supprimer(tmp);
                a.erreur(e.getMessage());
            } finally {
                a.majEnCours = false;
            }
        }
        private void ranger(File tmp, String p) throws IOException {
            File src = new File(tmp, p), dst = new File(a.dossierJeu(), p);
            File d = dst.getParentFile(); if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("dossier impossible : " + d);
            if (dst.exists() && !dst.delete()) throw new IOException("impossible de remplacer " + p);
            if (!src.renameTo(dst)) throw new IOException("impossible de ranger " + p);
        }
    }

    // la page est prévenue : avancement, fin (elle se recharge alors), ou échec
    void appelJs(String js) { runOnUiThread(new Js(this, js)); }
    void progres(int fait, int total) { appelJs("window.majProgres&&majProgres(" + fait + "," + total + ")"); }
    void fini(String version, int n) { appelJs("window.majFinie&&majFinie(" + JSONObject.quote(version) + "," + n + ")"); }
    void erreur(String m) { appelJs("window.majErreur&&majErreur(" + JSONObject.quote(m == null ? "" : m) + ")"); }

    private static class Js implements Runnable {
        private final MainActivity a; private final String js;
        Js(MainActivity x, String j) { a = x; js = j; }
        @Override public void run() { if (a.vue != null) a.vue.evaluateJavascript(js, null); }
    }

    /* ===== nouvel APK (seulement si la page le demande : changement de l'application elle-même) ===== */
    private static class Lancer implements Runnable {
        private final MainActivity a; private final String url;
        Lancer(MainActivity x, String u) { a = x; url = u; }
        @Override public void run() { a.telechargerApk(url); }
    }

    private static class Fin extends BroadcastReceiver {
        private final MainActivity a; private final DownloadManager dm;
        Fin(MainActivity x, DownloadManager d) { a = x; dm = d; }
        @Override public void onReceive(Context c, Intent i) {
            if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != a.telechargement) return;
            try { a.unregisterReceiver(this); } catch (Exception e) { }
            a.installer(dm, a.telechargement);
        }
    }

    /* télécharge le nouvel APK puis lance son installation (par-dessus l'ancienne version) */
    void telechargerApk(String url) {
        try {
            File ancien = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "battlepong.apk");
            if (ancien.exists()) ancien.delete();
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Battle Pong");
            req.setDescription("Nouvelle version de l'application");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "battlepong.apk");
            telechargement = dm.enqueue(req);
            BroadcastReceiver fin = new Fin(this, dm);
            IntentFilter filtre = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(fin, filtre, Context.RECEIVER_EXPORTED);
            else registerReceiver(fin, filtre);
            Toast.makeText(this, "Téléchargement de la nouvelle version…", Toast.LENGTH_SHORT).show();
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
