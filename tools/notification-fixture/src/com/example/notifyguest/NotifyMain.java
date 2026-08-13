package com.example.notifyguest;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Posts notifications, so the virtual device's notification service has something to catch.
 *
 * Two go out from onCreate, which makes a plain launch enough to prove the path end to end; the
 * buttons are there to post more and to cancel, which is what exercises the shade updating rather
 * than merely being populated once.
 */
public class NotifyMain extends Activity {

    private static final String TAG = "NOTIFYGUEST";
    private static final String CHANNEL = "fixture";

    private int next = 1;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        createChannel();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 160, 48, 48);
        root.setBackgroundColor(Color.parseColor("#101018"));

        TextView title = new TextView(this);
        title.setText("Notification fixture");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9AA0B0"));
        status.setText("posted: 0");
        root.addView(status);

        Button post = new Button(this);
        post.setText("Post a notification");
        post.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                postOne();
            }
        });
        root.addView(post);

        Button cancel = new Button(this);
        cancel.setText("Cancel all");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager().cancelAll();
                status.setText("cancelled");
                Log.i(TAG, "cancelled all notifications");
            }
        });
        root.addView(cancel);

        setContentView(root);

        postOne();
        postOne();
    }

    private NotificationManager manager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        manager().createNotificationChannel(
                new NotificationChannel(CHANNEL, "Fixture", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private void postOne() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setContentTitle("Fixture note " + next);
        builder.setContentText("Posted by the guest, note number " + next);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info);
        manager().notify(next, builder.build());
        Log.i(TAG, "posted notification " + next);
        next++;
        if (status != null) {
            status.setText("posted: " + (next - 1));
        }
    }
}
