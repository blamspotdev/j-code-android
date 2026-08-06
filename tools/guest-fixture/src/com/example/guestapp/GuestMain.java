package com.example.guestapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Test fixture for the JCode virtual-device container. Reports the identity it sees, so identity
 * faking can be verified, offers a second activity so intra-app navigation can be exercised, and
 * opens each kind of child window — dialog, popup menu, drop-down — because those are separate
 * windows rather than views and the container has to host them itself.
 */
public class GuestMain extends Activity {

    public static final String TAG = "GUESTAPP";

    /** Last child-window result, on screen so a screenshot alone shows the window was interactive. */
    private TextView picked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        String androidId = "?";
        try {
            androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }

        String report = "package   = " + getPackageName()
                + "\nprocess   = " + android.os.Process.myPid() + " uid=" + android.os.Process.myUid()
                + "\nBuild.MODEL = " + Build.MODEL
                + "\nBuild.DEVICE= " + Build.DEVICE
                + "\nBuild.FINGERPRINT=\n  " + Build.FINGERPRINT
                + "\nANDROID_ID = " + androidId
                + "\nfilesDir  = " + getFilesDir()
                + "\ncpus      = " + Runtime.getRuntime().availableProcessors();

        Log.i(TAG, "GuestMain.onCreate\n" + report);

        // Top-aligned and scrollable: an embedded guest measures the whole screen but is only shown
        // an editor tab's worth of it, so anything centred would be off the bottom of the tab.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#102027"));
        root.setPadding(48, 24, 48, 24);
        root.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("Guest App — running inside JCode");
        title.setTextColor(Color.parseColor("#80CBC4"));
        title.setTextSize(22);
        root.addView(title);

        Button b2 = new Button(this);
        b2.setText("Open second activity");
        b2.setOnClickListener(v -> startActivity(new Intent(GuestMain.this, SecondActivity.class)));
        root.addView(b2);

        Button dialog = new Button(this);
        dialog.setText("Show dialog");
        dialog.setOnClickListener(v -> new AlertDialog.Builder(GuestMain.this)
                .setTitle("Dialog")
                .setMessage("This is a real Dialog window, not a view.")
                .setPositiveButton("OK", (d, w) -> picked("dialog OK"))
                .setNegativeButton("Cancel", (d, w) -> picked("dialog Cancel"))
                .show());
        root.addView(dialog);

        Button menu = new Button(this);
        menu.setText("Show popup menu");
        menu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(GuestMain.this, v);
            popup.getMenu().add("First item");
            popup.getMenu().add("Second item");
            popup.getMenu().add("Third item");
            popup.setOnMenuItemClickListener(item -> {
                picked("menu " + item.getTitle());
                return true;
            });
            popup.show();
        });
        root.addView(menu);

        // Built before the spinner: selecting the adapter's first item calls back right away.
        picked = new TextView(this);
        picked.setTextColor(Color.parseColor("#FFD54F"));
        picked.setTextSize(15);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> items = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[] {"Spinner one", "Spinner two", "Spinner three"});
        items.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(items);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int pos, long id) {
                picked("spinner " + parent.getItemAtPosition(pos));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(spinner);
        root.addView(picked);

        TextView tv = new TextView(this);
        tv.setText("\n" + report);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(tv);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#102027"));
        scroll.addView(root);
        setContentView(scroll);
    }

    private void picked(String what) {
        Log.i(TAG, "picked: " + what);
        picked.setText("last: " + what);
    }
}
