package org.telegram.messenger.kgram;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

/**
 * OpenIM-mode system notifications (status-bar / shade).
 * Telegram's NotificationsController path still depends on MTProto unread pipelines;
 * this posts a minimal NotificationCompat when WS delivers inbound chat messages.
 */
public final class KgramNotifications {
    private KgramNotifications() {}

    public static final String CHANNEL_ID = "kgram_messages";
    private static final String CHANNEL_NAME = "Messages";
    private static final int BASE_NOTIFY_ID = 71000;

    private static volatile boolean channelReady;

    public static void ensureChannel(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        if (channelReady) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("OpenIM chat messages");
                ch.enableVibration(true);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
            channelReady = true;
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static boolean canPost(Context ctx) {
        if (ctx == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    /**
     * Show / update a per-dialog notification for an inbound OpenIM text message.
     * Caller must already filter: not outbound, chat not currently open.
     */
    public static void notifyIncoming(int account, long dialogId, String title, String body, int messageId) {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || dialogId <= 0) {
            return;
        }
        if (!KgramServerConfig.USE_OPENIM_AUTH) {
            return;
        }
        ensureChannel(ctx);
        if (!canPost(ctx)) {
            FileLog.d("kgram notify skip: no POST_NOTIFICATIONS / disabled");
            return;
        }
        String safeTitle = (title != null && title.length() > 0) ? title : ("User " + dialogId);
        String safeBody = body != null ? body : "";
        if (safeBody.length() == 0) {
            safeBody = "新消息";
        }

        try {
            Intent intent = new Intent(ctx, LaunchActivity.class);
            intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("userId", dialogId);
            intent.putExtra("currentAccount", account);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx,
                    (int) (dialogId % 100000),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(safeTitle)
                    .setContentText(safeBody)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(safeBody))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setNumber(1)
                    .setColor(0xff11acfa);

            int notifyId = notifyIdFor(account, dialogId);
            NotificationManagerCompat.from(ctx).notify(notifyId, builder.build());
            FileLog.d("kgram notify posted dialog=" + dialogId + " mid=" + messageId + " id=" + notifyId);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void cancelDialog(int account, long dialogId) {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || dialogId <= 0) {
            return;
        }
        try {
            NotificationManagerCompat.from(ctx).cancel(notifyIdFor(account, dialogId));
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            NotificationsController.getInstance(account).removeNotificationsForDialog(dialogId);
        } catch (Throwable ignore) {
        }
    }

    private static int notifyIdFor(int account, long dialogId) {
        long mixed = (dialogId & 0x7fffffffL) ^ ((long) (account + 1) << 20);
        return BASE_NOTIFY_ID + (int) (mixed % 200000);
    }

    /** Resolve a display name for notifications from MessagesController cache. */
    public static String resolvePeerTitle(int account, long dialogId, String fallback) {
        try {
            TLRPC.User u = org.telegram.messenger.MessagesController.getInstance(account).getUser(dialogId);
            if (u != null) {
                String n = org.telegram.messenger.UserObject.getUserName(u);
                if (n != null && n.length() > 0) {
                    return n;
                }
                if (u.first_name != null && u.first_name.length() > 0) {
                    return u.first_name;
                }
            }
        } catch (Throwable ignore) {
        }
        return fallback != null && fallback.length() > 0 ? fallback : ("User " + dialogId);
    }
}
