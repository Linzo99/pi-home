package com.sall.piairplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver when the Pi starts, so it's listed without opening anything. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        AirPlayService.start(c);
    }
}
