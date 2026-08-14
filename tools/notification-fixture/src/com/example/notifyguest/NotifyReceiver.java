package com.example.notifyguest;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Where a notification button lands.
 *
 * A `PendingIntent` that fires into a *receiver* is the sharpest test of the path: it proves the
 * token the guest minted was real, that the container let it out, and that the broadcast came back
 * to a component the real system has never heard of. A button that only started an activity could
 * be explained by the tab hosting a launch, which is a different mechanism.
 */
public class NotifyReceiver extends BroadcastReceiver {

    private static final String TAG = "NOTIFYGUEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        String what = intent.getAction();
        int id = intent.getIntExtra("id", -1);
        Log.i(TAG, "action fired: " + what + " for note " + id);
        Toast.makeText(context, "Action: " + what + " (" + id + ")", Toast.LENGTH_SHORT).show();
        if (NotifyMain.ACTION_DISMISS.equals(what) && id >= 0) {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(id);
        }
    }
}
