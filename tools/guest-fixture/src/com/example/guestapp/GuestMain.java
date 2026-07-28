package com.example.guestapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test fixture for the JCode virtual-device container. Reports the identity it sees, so identity
 * faking can be verified, and offers a second activity so intra-app navigation can be exercised.
 */
public class GuestMain extends Activity {

    public static final String TAG = "GUESTAPP";

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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#102027"));
        root.setPadding(48, 48, 48, 48);
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Guest App — running inside JCode");
        title.setTextColor(Color.parseColor("#80CBC4"));
        title.setTextSize(22);
        root.addView(title);

        TextView tv = new TextView(this);
        tv.setText("\n" + report);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(tv);

        Button b2 = new Button(this);
        b2.setText("Open second activity");
        b2.setOnClickListener(v -> startActivity(new Intent(GuestMain.this, SecondActivity.class)));
        root.addView(b2);

        setContentView(root);
    }
}
