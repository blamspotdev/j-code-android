package com.example.guestapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

/** Second screen — exercises intra-app startActivity, which the container has to intercept. */
public class SecondActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(GuestMain.TAG, "SecondActivity.onCreate — intra-app navigation worked");
        TextView tv = new TextView(this);
        tv.setText("Second activity\n\nIntra-app navigation works.");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.parseColor("#1B3A4B"));
        setContentView(tv);
    }
}
