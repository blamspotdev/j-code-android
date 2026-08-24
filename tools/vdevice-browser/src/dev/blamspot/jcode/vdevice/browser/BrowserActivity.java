package dev.blamspot.jcode.vdevice.browser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * A browser for the virtual device.
 *
 * It exists so the device has something on it that can open a URL without reaching for the phone's
 * browser — which would take the user out of JCode and open the page under their own profile, with
 * their own cookies and their own signed-in accounts. Everything this loads stays inside the device
 * and is wiped with it.
 *
 * ### Built without resources, and still not stock
 *
 * There is no `res/` here: the whole thing is one Java file so that plain javac + d8 + aapt2 can
 * produce it (see the README). That is a packaging constraint, not a licence to look unfinished —
 * a stock `EditText` and a stock `Button` on a dark bar give you a grey slab and a pale underline
 * that belong to neither the page nor the device. So every surface is drawn here: rounded
 * backgrounds from {@link GradientDrawable}, the device's own palette, glyph buttons instead of
 * system ones, and a hairline under the chrome rather than a raised bar.
 *
 * The address bar shows the **host** while a page is loaded and the whole URL while it is being
 * edited, which is the distinction a person actually wants from it.
 */
public class BrowserActivity extends Activity {

    private static final String HOME = "https://duckduckgo.com/";

    /** The device's own palette, so the browser looks like part of it rather than a visitor. */
    private static final int CHROME = Color.parseColor("#12141A");
    private static final int PAGE = Color.parseColor("#2B2D31");
    private static final int FIELD = Color.parseColor("#1E2129");
    private static final int HAIRLINE = Color.parseColor("#2C303A");
    private static final int FOREGROUND = Color.parseColor("#E6E8EF");
    private static final int MUTED = Color.parseColor("#8B93A7");
    private static final int ACCENT = Color.parseColor("#7FA6FF");

    private WebView web;
    private EditText address;
    private ProgressBar progress;
    private TextView back;
    private TextView forward;

    /** The page's real URL. The address bar may be showing only its host. */
    private String current = "";

    /** Set while the page is driving the address bar, so editing is not fought over. */
    private boolean syncing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        root.addView(chrome(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

    /** The bar, its progress line, and the hairline that separates the two from the page. */
    private View chrome() {
        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setBackgroundColor(CHROME);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        back = glyph("‹", "Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web.canGoBack()) {
                    web.goBack();
                }
            }
        });
        bar.addView(back);

        forward = glyph("›", "Forward");
        forward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web.canGoForward()) {
                    web.goForward();
                }
            }
        });
        bar.addView(forward);

        bar.addView(field(), new LinearLayout.LayoutParams(0, dp(34), 1f));

        TextView reload = glyph("⟳", "Reload");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.reload();
            }
        });
        bar.addView(reload);

        chrome.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);
        progress.getProgressDrawable().setColorFilter(
                ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        chrome.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        View hairline = new View(this);
        hairline.setBackgroundColor(HAIRLINE);
        chrome.addView(hairline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        return chrome;
    }

    /** The address pill: rounded, filled, and without the stock underline. */
    private View field() {
        FrameLayout holder = new FrameLayout(this);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(FIELD);
        pill.setCornerRadius(dp(17));
        holder.setBackground(pill);

        address = new EditText(this);
        address.setBackground(null);
        address.setSingleLine(true);
        address.setTextColor(FOREGROUND);
        address.setHintTextColor(MUTED);
        address.setHint("Search or type a URL");
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        address.setPadding(dp(14), 0, dp(14), 0);
        address.setGravity(Gravity.CENTER_VERTICAL);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent event) {
                go();
                return true;
            }
        });
        // Focused, it shows the whole URL ready to be replaced; unfocused, just the host. Nobody
        // reading an address bar wants a query string, and nobody editing one wants it hidden.
        address.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                syncing = true;
                if (focused) {
                    address.setText(current);
                    address.selectAll();
                } else {
                    address.setText(host(current));
                }
                syncing = false;
            }
        });
        holder.addView(address, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return holder;
    }

    /** A flat, round, tappable glyph — a system Button on this bar looks like a visitor. */
    private TextView glyph(String text, String description) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setContentDescription(description);
        view.setTextColor(FOREGROUND);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        GradientDrawable round = new GradientDrawable();
        round.setColor(Color.TRANSPARENT);
        round.setCornerRadius(dp(17));
        view.setBackground(round);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
        view.setLayoutParams(params);
        return view;
    }

    private void configure(WebView view) {
        view.setBackgroundColor(PAGE);
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

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }
                // A page of our own rather than the platform's, which is a white screen with a
                // sad-face glyph and reads as a crash on a device this dark.
                v.loadDataWithBaseURL(null, offline(), "text/html", "utf-8", null);
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int percent) {
                progress.setProgress(percent);
                progress.setVisibility(percent >= 100 ? View.INVISIBLE : View.VISIBLE);
                steps();
            }
        });
    }

    /** The device has no network of its own to report on, so it says what it can and stops there. */
    private String offline() {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;align-items:center;"
                + "justify-content:center;background:#2B2D31;color:#8B93A7;"
                + "font:15px/1.5 sans-serif;text-align:center;padding:24px}"
                + "b{color:#E6E8EF;display:block;margin-bottom:6px;font-size:16px}</style></head>"
                + "<body><div><b>That page did not load</b>"
                + "The device reaches the network through JCode. Check the connection, or try "
                + "another address.</div></body></html>";
    }

    private void show(String url) {
        current = url == null ? "" : url;
        steps();
        if (address.hasFocus()) {
            return;
        }
        syncing = true;
        address.setText(host(current));
        syncing = false;
    }

    /** Dims the arrows that would do nothing, rather than leaving them looking live. */
    private void steps() {
        back.setAlpha(web.canGoBack() ? 1f : 0.3f);
        forward.setAlpha(web.canGoForward() ? 1f : 0.3f);
    }

    /** The part of a URL worth showing when it is not being edited. */
    private String host(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        Uri parsed = Uri.parse(url);
        String name = parsed.getHost();
        if (TextUtils.isEmpty(name)) {
            return url;
        }
        return name.startsWith("www.") ? name.substring(4) : name;
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
        address.clearFocus();
        web.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (address != null && address.hasFocus()) {
            address.clearFocus();
            return;
        }
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
