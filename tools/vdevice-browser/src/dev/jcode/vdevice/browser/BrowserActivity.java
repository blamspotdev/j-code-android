package dev.jcode.vdevice.browser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

/**
 * A browser for the virtual device.
 *
 * Deliberately plain: an address bar, a page, and Back. It exists so the device has something on it
 * that can open a URL without reaching for the phone's browser — which would take the user out of
 * J Code and open the page under their own profile, with their own cookies and their own signed-in
 * accounts. Everything this loads stays inside the device and is wiped with it.
 *
 * Built resource-free so plain javac + d8 + aapt2 can produce it; see the README.
 */
public class BrowserActivity extends Activity {

    private static final String HOME = "https://duckduckgo.com/";
    private static final int CHROME = Color.parseColor("#12141A");
    private static final int FOREGROUND = Color.parseColor("#E6E8EF");

    private WebView web;
    private EditText address;
    private ProgressBar progress;

    /** Set while the page is driving the address bar, so editing it is not fought over. */
    private boolean syncing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CHROME);

        root.addView(bar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        configure(web);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        String start = HOME;
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null) {
            start = data.toString();
        }
        web.loadUrl(start);
    }

    private View bar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(FOREGROUND);
        address.setHintTextColor(Color.parseColor("#9AA0B0"));
        address.setHint("Search or type a URL");
        address.setTextSize(14f);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent event) {
                go();
                return true;
            }
        });
        bar.addView(address, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button go = new Button(this);
        go.setText("Go");
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go();
            }
        });
        bar.addView(go, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return bar;
    }

    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                // Everything stays in here. Letting a link out would hand the page to the phone's
                // browser, which is the one thing this exists to avoid.
                v.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                show(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                show(url);
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int percent) {
                progress.setProgress(percent);
                progress.setVisibility(percent >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void show(String url) {
        syncing = true;
        address.setText(url);
        syncing = false;
    }

    /** Loads what is typed: a URL as written, anything else as a search. */
    private void go() {
        if (syncing) {
            return;
        }
        String typed = address.getText().toString().trim();
        if (TextUtils.isEmpty(typed)) {
            return;
        }
        String url;
        if (typed.startsWith("http://") || typed.startsWith("https://")) {
            url = typed;
        } else if (typed.contains(".") && !typed.contains(" ")) {
            url = "https://" + typed;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(typed);
        }
        web.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) {
            web.loadUrl(intent.getData().toString());
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
