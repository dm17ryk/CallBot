package com.essence.callbot;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ADB command surface. `am broadcast` sends an ORDERED broadcast, so the
 * JSON we put in setResultData() is printed synchronously by the adb shell:
 *
 *   adb shell am broadcast -p com.essence.callbot -a com.essence.callbot.CMD \
 *       --es cmd status
 *   -> Broadcast completed: result=-1, data="{...json...}"
 */
public class CommandReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.essence.callbot.CMD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String cmd = intent.getStringExtra("cmd");
        String result = CommandRouter
                .execute(context, "ADB", cmd, intent.getExtras())
                .toString();
        setResultCode(Activity.RESULT_OK);
        setResultData(result);
    }
}
