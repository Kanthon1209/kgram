package org.telegram.messenger.kgram;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.ui.LaunchActivity;

/**
 * OpenIM-mode system notifications (status-bar / shade).
 * Telegram's NotificationsController path still depends on MTProto unread pipelines;
 * this posts a minimal NotificationCompat when WS delivers inbound chat messages.
 */
public final class KgramNotifications {
    private KgramNotifications() {}

    public static final String CHANNEL_ID = "kgram_messages_v2";
    private static final String CHANNEL_NAME = "消息";
    private static final int BASE_NOTIFY_ID = 71000;

    private static volatile boolean channelReady;
    private static volatile boolean permissionAsked;

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
            // Drop legacy silent/low channel from v0.1.13 if present.
            try {
                nm.deleteNotificationChannel("kgram_messages");
            } catch (Throwable ignore) {
            }
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("OpenIM 聊天消息");
                ch.enableVibration(true);
                ch.setShowBadge(true);
                ch.enableLights(true);
                ch.setLockscreenVisibility(1); // VISIBILITY_PUBLIC
                try {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    ch.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attrs);
                } catch (Throwable ignore) {
                }
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
     * Ask for POST_NOTIFICATIONS once after OpenIM session activates (API 33+).
     * Uses TG PermissionRequest so LaunchActivity receives the result.
     */
    public static void requestPermissionIfNeeded() {
        if (permissionAsked) {
            return;
        }
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        if (canPost(ctx)) {
            permissionAsked = true;
            return;
        }
        permissionAsked = true;
        try {
            PermissionRequest.requestPermission(Manifest.permission.POST_NOTIFICATIONS, granted -> {
                FileLog.d("kgram POST_NOTIFICATIONS granted=" + granted);
                ensureChannel(ApplicationLoader.applicationContext);
            });
        } catch (Throwable t) {
            FileLog.e(t);
            // Fallback: direct Activity request if PermissionRequest path fails.
            try {
                Activity a = LaunchActivity.instance;
                if (a != null) {
                    a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 71001);
                }
            } catch (Throwable t2) {
                FileLog.e(t2);
            }
        }
    }

    /**
     * Show / update a per-dialog notification for an inbound OpenIM text message.
     * Caller must already filter: not outbound, chat not currently open.
     */
    public static void notifyIncoming(int account, long dialogId, String title, String body, int messageId) {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || dialogId <= 0) {
            FileLog.d("kgram notify skip: no ctx/dialog");
            return;
        }
        if (!KgramServerConfig.USE_OPENIM_AUTH) {
            return;
        }
        ensureChannel(ctx);
        if (!canPost(ctx)) {
            FileLog.d("kgram notify skip: no POST_NOTIFICATIONS / disabled — requesting");
            requestPermissionIfNeeded();
            return;
        }
        String safeTitle = (title != null && title.length() > 0) ? title : ("User " + dialogId);
        String safeBody = body != null ? body : "";
        if (safeBody.length() == 0) {
            safeBody = "新消息";
        }

        try {
            Intent intent = new Intent(ctx, LaunchActivity.class);
            // Stable action so PendingIntent updates instead of stacking random ones.
            intent.setAction("org.telegram.messenger.kgram.openchat");
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("userId", dialogId);
            intent.putExtra("dialogId", dialogId);
            intent.putExtra("currentAccount", account);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx,
                    (int) (dialogId % 100000) + account * 1000,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(safeTitle)
                    .setContentText(safeBody)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(safeBody))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setNumber(1)
                    .setColor(0xff11acfa);

            int notifyId = notifyIdFor(account, dialogId);
            NotificationManagerCompat.from(ctx).notify(notifyId, builder.build());
            FileLog.d("kgram notify posted dialog=" + dialogId + " mid=" + messageId + " id=" + notifyId);
        } catch (Throwable t) {
            FileLog.e("kgram notify failed");
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
