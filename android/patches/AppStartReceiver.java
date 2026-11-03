/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AppStartReceiver extends BroadcastReceiver {

    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AndroidUtilities.runOnUIThread(() -> {
                SharedConfig.loadConfig();
                if (SharedConfig.passcodeHash.length() > 0) {
                    SharedConfig.appLocked = true;
                    SharedConfig.saveConfig();
                }
                ApplicationLoader.startPushService();
                // kgram OpenIM: also restart FGS + WS after reboot when session exists.
                try {
                    if (org.telegram.messenger.kgram.KgramServerConfig.USE_OPENIM_AUTH
                            && org.telegram.messenger.kgram.KgramSession.hasSession(context)) {
                        Log.e("kgram-fgs", "BOOT_COMPLETED → ensure KgramPushService");
                        org.telegram.messenger.kgram.KgramPushService.ensureStarted(context);
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }
    }
}
