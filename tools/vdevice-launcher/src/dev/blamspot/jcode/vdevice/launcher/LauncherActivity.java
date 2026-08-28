package dev.blamspot.jcode.vdevice.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The device's home screen.
 *
 * Everything here is a question any launcher asks: {@code queryIntentActivities} for what is
 * installed, {@code loadLabel}/{@code loadIcon} for how to draw it, {@code startActivity} to open
 * it. None of it is special to this device — the container answers those calls with the device's own
 * apps instead of the phone's, and that is the whole of the difference.
 *
 * Built in code rather than from a layout for the same reason the device's other apps are: the
 * pipeline that builds these is {@code aapt2} + {@code javac} + {@code d8} with no Gradle, and a
 * layout inflated by id is one more thing to keep in step for no gain at this size.
 */
public final class LauncherActivity extends Activity {

    /** Matches the wallpaper the container paints, so the two never disagree at the edges. */
    private static final int BACKGROUND = 0xFF11151C;
    private static final int LABEL = 0xD9FFFFFF;
    private static final int MUTED = 0x8CFFFFFF;

    private static final int COLUMNS_MIN = 3;
    private static final float CELL_MIN_DP = 88f;
    private static final float ICON_DP = 46f;

    private LinearLayout grid;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BACKGROUND);
        page.setFillViewport(true);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(12f), dp(16f), dp(12f), dp(16f));
        page.addView(grid, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(page);
    }

    /**
     * Rebuilt on every resume, not only on create.
     *
     * The home screen is what somebody comes back to after installing, uninstalling or force-stopping
     * something, and this activity is not destroyed in between — it is the one app on the device
     * that is always there. A list built once would be a list that went stale the first time
     * anything changed.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Posted, not called straight out: the column count comes from how wide the grid actually
        // is, and on the first resume it has not been laid out yet. Measured against the display
        // metrics instead it read the screen the device had before its profile was applied and
        // packed six columns onto a 411dp phone, wrapping every label.
        grid.post(new Runnable() {
            @Override
            public void run() {
                populate();
            }
        });
    }

    /** A device that changes shape under a running app re-flows its home screen with it. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        grid.post(new Runnable() {
            @Override
            public void run() {
                populate();
            }
        });
    }

    private void populate() {
        grid.removeAllViews();
        List<ResolveInfo> apps = installed();
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No app installed");
            empty.setTextColor(MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48f), 0, 0);
            grid.addView(empty);
            return;
        }

        int columns = columns();
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(cell(apps.get(i)), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // The last row is padded with empty cells so its icons keep the column width the rows above
        // set, instead of spreading across whatever space is left.
        int remainder = apps.size() % columns;
        if (remainder != 0 && row != null) {
            for (int i = remainder; i < columns; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
    }

    /**
     * What this device can launch.
     *
     * The plain platform query. On a phone it answers with the phone's apps; here the container
     * answers it with the device's, which is the point — nothing in this app knows it is running
     * anywhere unusual.
     */
    private List<ResolveInfo> installed() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found;
        try {
            found = getPackageManager().queryIntentActivities(main, 0);
        } catch (Throwable t) {
            found = Collections.emptyList();
        }
        List<ResolveInfo> apps = new ArrayList<>(found == null ? Collections.emptyList() : found);
        final PackageManager packages = getPackageManager();
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return label(packages, a).compareToIgnoreCase(label(packages, b));
            }
        });
        return apps;
    }

    private View cell(final ResolveInfo app) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4f), dp(10f), dp(4f), dp(10f));
        cell.setBackground(ripple());
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(label(getPackageManager(), app));
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                open(app);
            }
        });

        ImageView icon = new ImageView(this);
        Drawable art = null;
        try {
            art = app.loadIcon(getPackageManager());
        } catch (Throwable ignored) {
            // An archive whose icon will not resolve still belongs on the home screen; it gets the
            // placeholder rather than being dropped from a list of what is installed.
        }
        if (art != null) {
            icon.setImageDrawable(art);
        } else {
            icon.setBackground(rounded(0x33FFFFFF, dp(10f)));
        }
        cell.addView(icon, new LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)));

        TextView name = new TextView(this);
        name.setText(label(getPackageManager(), app));
        name.setTextColor(LABEL);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        below.topMargin = dp(6f);
        cell.addView(name, below);
        return cell;
    }

    /**
     * Start an app.
     *
     * By component and with NEW_TASK, which is what a launcher sends. The container hosts it on the
     * device's screen; nothing here needs to know that.
     */
    private void open(ResolveInfo app) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Throwable ignored) {
            // An app that will not start is the container's to report on the device's own screen —
            // the launcher saying so as well would be two messages about one failure.
        }
    }

    private static String label(PackageManager packages, ResolveInfo app) {
        try {
            CharSequence text = app.loadLabel(packages);
            if (text != null && text.length() > 0) return text.toString();
        } catch (Throwable ignored) {
            // Falls through to the package name, which is always there.
        }
        return app.activityInfo != null ? app.activityInfo.packageName : "";
    }

    /**
     * As many columns as fit at {@link #CELL_MIN_DP}, and never fewer than three.
     *
     * Measured from the grid's own width where there is one. The display metrics are the fallback
     * for the very first pass, and only that: they describe the screen, which is not the same as the
     * space this list was given.
     */
    private int columns() {
        float density = getResources().getDisplayMetrics().density;
        float pixels = grid.getWidth() > 0
                ? grid.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        float width = pixels / density;
        return Math.max(COLUMNS_MIN, (int) (width / CELL_MIN_DP));
    }

    private Drawable rounded(int colour, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(radius);
        return shape;
    }

    private Drawable ripple() {
        TypedValue value = new TypedValue();
        boolean found = getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true);
        if (found && value.resourceId != 0) {
            try {
                return getResources().getDrawable(value.resourceId, getTheme());
            } catch (Throwable ignored) {
                // No ripple is a cosmetic loss, not a reason to have no home screen.
            }
        }
        return rounded(Color.TRANSPARENT, 0);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
