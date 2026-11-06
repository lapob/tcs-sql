package it.topcash.studio;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Top Cash Studio for Android: a secure shell around the shared Top Cash server (same UI as PC and browser).
 * Sign-in cookies persist on the device; phone, e-mail and map links open the native apps; exports are downloaded.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "topcash", KEY_SERVER = "server";
    private static final int FILE_REQUEST = 41;
    private FrameLayout root;
    private WebView web;
    private String server;
    private ValueCallback<Uri[]> fileCallback;
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean showingError;

    private static final int ACCENT = 0xFF0A6BDA, TEXT = 0xFF16161D, MUTED = 0xFF55566A, BG = 0xFFF6F6F8;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        applyInsets(root);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        server = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER, null);
        if (server == null) showSetup(null); else showWeb(state);
        watchNetwork();
    }

    /** Keeps content out of the status / navigation bars (edge-to-edge on Android 15+). */
    private void applyInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else { l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop(); r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom(); }
            view.setPadding(l, t, r, b);
            return insets;
        });
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String normalize(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "http://" + s;
        Uri u = Uri.parse(s);
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        int port = u.getPort();
        if (port < 0 && scheme.equals("http") && !s.matches("(?i)https?://[^/]+:\\d+.*")) port = 8470; // default Top Cash port
        return scheme + "://" + u.getHost() + (port > 0 ? ":" + port : "") + "/";
    }

    // ── first start: server address
    private void showSetup(String error) {
        if (web != null) { root.removeView(web); }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(48), dp(28), dp(28));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        box.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView title = text("Top Cash Studio", 24, TEXT, true);
        title.setPadding(0, dp(18), 0, dp(6));
        box.addView(title);
        TextView sub = text("Scrivi l'indirizzo del server Top Cash dell'ufficio.\nLo trovi in Impostazioni sul PC (es. 192.168.1.220).", 14, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        box.addView(sub);
        final EditText input = new EditText(this);
        input.setHint("192.168.1.220:8470");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER, "");
        input.setText(saved.replace("http://", "").replace("/", ""));
        GradientDrawable field = new GradientDrawable();
        field.setColor(Color.WHITE); field.setCornerRadius(dp(14)); field.setStroke(dp(1), 0x22000000);
        input.setBackground(field);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(26);
        box.addView(input, lp);
        TextView err = text(error == null ? "" : error, 13, 0xFFE5484D, false);
        err.setPadding(0, dp(10), 0, 0);
        box.addView(err);
        Button go = new Button(this);
        go.setText("Collega");
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setTextSize(16);
        GradientDrawable btn = new GradientDrawable();
        btn.setColor(ACCENT); btn.setCornerRadius(dp(14));
        go.setBackground(btn);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        bp.topMargin = dp(18);
        box.addView(go, bp);
        TextView note = text("Funziona sulla rete Wi-Fi dell'ufficio. I dati restano sul server aziendale.", 12, MUTED, false);
        note.setGravity(Gravity.CENTER); note.setPadding(0, dp(22), 0, 0);
        box.addView(note);
        View.OnClickListener connect = v -> {
            String url = normalize(input.getText().toString());
            if (url == null) { err.setText("Indirizzo non valido."); return; }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER, url).apply();
            server = url;
            root.removeAllViews();
            showWeb(null);
        };
        go.setOnClickListener(connect);
        input.setOnEditorActionListener((v, id, ev) -> { connect.onClick(v); return true; });
        root.removeAllViews();
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    // ── main screen: the shared web app
    private void showWeb(Bundle state) {
        showingError = false;
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " TopCashStudioAndroid/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        final String host = Uri.parse(server).getHost();
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ((scheme.equals("http") || scheme.equals("https")) && host.equalsIgnoreCase(u.getHost())) return false; // stays in the app
                openExternal(u);
                return true;
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError e) {
                if (req.isForMainFrame()) showError("Il server Top Cash non risponde.\nControlla di essere collegato al Wi-Fi dell'ufficio.");
            }
            @Override public void onPageFinished(WebView v, String url) { CookieManager.getInstance().flush(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_REQUEST); }
                catch (ActivityNotFoundException ex) { fileCallback = null; return false; }
                return true;
            }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String name = URLUtil.guessFileName(url, disposition, mime);
                r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                r.addRequestHeader("User-Agent", ua);
                r.setMimeType(mime);
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Download avviato: " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception ex) { Toast.makeText(this, "Download non riuscito", Toast.LENGTH_LONG).show(); }
        });
        root.removeAllViews();
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (state != null && web.restoreState(state) != null) return;
        web.loadUrl(server);
    }

    private void openExternal(Uri u) {
        try {
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            Intent i = scheme.equals("tel") ? new Intent(Intent.ACTION_DIAL, u)
                     : scheme.equals("mailto") ? new Intent(Intent.ACTION_SENDTO, u)
                     : new Intent(Intent.ACTION_VIEW, u);
            startActivity(i);
        } catch (ActivityNotFoundException ex) { Toast.makeText(this, "Nessuna app disponibile per aprire il collegamento", Toast.LENGTH_SHORT).show(); }
    }

    private void showError(String message) {
        if (showingError) return;
        showingError = true;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setBackgroundColor(BG);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        box.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView t = text("Server non raggiungibile", 20, TEXT, true);
        t.setPadding(0, dp(18), 0, dp(8)); box.addView(t);
        TextView m = text(message, 14, MUTED, false); m.setGravity(Gravity.CENTER); box.addView(m);
        Button retry = new Button(this); retry.setText("Riprova"); retry.setAllCaps(false); retry.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(ACCENT); bg.setCornerRadius(dp(14)); retry.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(50)); lp.topMargin = dp(24);
        box.addView(retry, lp);
        Button change = new Button(this); change.setText("Cambia server"); change.setAllCaps(false); change.setTextColor(ACCENT); change.setBackgroundColor(Color.TRANSPARENT);
        box.addView(change, new LinearLayout.LayoutParams(dp(220), dp(48)));
        retry.setOnClickListener(v -> { root.removeView(box); showingError = false; web.reload(); });
        change.setOnClickListener(v -> showSetup(null));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Back on Wi-Fi after an outage: reload automatically. */
    private void watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                runOnUiThread(() -> { if (showingError && web != null) { root.removeViewAt(root.getChildCount() - 1); showingError = false; web.reload(); } });
            }
        };
        try { cm.registerDefaultNetworkCallback(netCallback); } catch (Exception ignored) {}
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        } else super.onActivityResult(req, result, data);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.getParent() != null && web.canGoBack() && !showingError) { web.goBack(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); if (web != null) web.saveState(out); }
    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() {
        if (netCallback != null && Build.VERSION.SDK_INT >= 24) try { ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
