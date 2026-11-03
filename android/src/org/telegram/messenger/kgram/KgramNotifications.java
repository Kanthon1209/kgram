package org.telegram.messenger.kgram;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.ui.LaunchActivity;

/**
 * OpenIM-mode system notifications (status-bar / shade).
 * Does not depend on MTProto NotificationsController pipelines.
 */
public final class KgramNotifications {
    private static void dlog(String msg) {
        if (BuildVars.LOGS_ENABLED) {
            Log.d(TAG, msg);
        }
    }

    private KgramNotifications() {}

    private static final String TAG = "kgram-notify";

    /** Bumped channel id so OEM caches of a silent/broken channel are discarded. */
    public static final String CHANNEL_ID = "kgram_messages_v3";
    private static final String CHANNEL_NAME = "消息";
    private static final int BASE_NOTIFY_ID = 72000;
    private static final int TEST_NOTIFY_ID = 71998;

    private static volatile boolean channelReady;
    private static volatile long lastPermissionAskMs;
    private static volatile boolean permissionGrantedSticky;

    public static void ensureChannel(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            // Drop older channels that may have been created muted / low.
            for (String old : new String[]{"kgram_messages", "kgram_messages_v2"}) {
                try {
                    nm.deleteNotificationChannel(old);
                } catch (Throwable ignore) {
                }
            }
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("OpenIM 聊天消息");
                ch.enableVibration(true);
                ch.setShowBadge(true);
                ch.enableLights(true);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                ch.setBypassDnd(false);
                try {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    ch.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attrs);
                } catch (Throwable ignore) {
                }
                nm.createNotificationChannel(ch);
                dlog("created channel " + CHANNEL_ID + " HIGH");
            } else if (existing.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
                // Cannot raise importance of an existing channel; recreate under new id next ship.
                dlog("channel " + CHANNEL_ID + " importance too low=" + existing.getImportance());
            }
            channelReady = true;
        } catch (Throwable t) {
            Log.e(TAG, "ensureChannel failed", t);
            FileLog.e(t);
        }
    }

    public static boolean canPost(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                return false;
            }
        } catch (Throwable ignore) {
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * Ask for POST_NOTIFICATIONS after OpenIM session activates (API 33+).
     * Retries periodically until granted (does NOT sticky-fail forever like v0.1.14).
     */
    public static void requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        if (canPost(ctx)) {
            permissionGrantedSticky = true;
            ensureChannel(ctx);
            return;
        }
        long now = System.currentTimeMillis();
        // Don't spam the system dialog; retry at most every 20s.
        if (now - lastPermissionAskMs < 20_000L) {
            dlog("permission ask skipped (cooldown)");
            return;
        }
        lastPermissionAskMs = now;
        dlog("requesting POST_NOTIFICATIONS");
        try {
            PermissionRequest.requestPermission(Manifest.permission.POST_NOTIFICATIONS, granted -> {
                dlog("POST_NOTIFICATIONS granted=" + granted);
                FileLog.e("kgram POST_NOTIFICATIONS granted=" + granted);
                if (granted) {
                    permissionGrantedSticky = true;
                    ensureChannel(ApplicationLoader.applicationContext);
                    postSelfTest("通知已开启", "之后后台来消息会出现在通知栏");
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "PermissionRequest failed", t);
            FileLog.e(t);
            try {
                Activity a = LaunchActivity.instance;
                if (a != null) {
                    a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 71001);
                } else {
                    Log.e(TAG, "no LaunchActivity.instance for permission");
                }
            } catch (Throwable t2) {
                Log.e(TAG, "direct requestPermissions failed", t2);
                FileLog.e(t2);
            }
        }
    }

    /** One-shot verification notification after permission grant / FGS boot. */
    public static void postSelfTest(String title, String body) {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || !canPost(ctx)) {
            dlog("self-test skipped canPost=" + canPost(ctx));
            return;
        }
        ensureChannel(ctx);
        try {
            Intent intent = new Intent(ctx, LaunchActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.msg_check_s)
                    .setContentTitle(title != null ? title : "kgram")
                    .setContentText(body != null ? body : "通知测试")
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentIntent(pi)
                    .setColor(0xff11acfa);
            postRaw(ctx, TEST_NOTIFY_ID, builder.build());
            dlog("self-test posted");
        } catch (Throwable t) {
            // Fallback icon if msg_check_s missing
            try {
                NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.drawable.notification)
                        .setContentTitle(title != null ? title : "kgram")
                        .setContentText(body != null ? body : "通知测试")
                        .setAutoCancel(true)
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);
                postRaw(ctx, TEST_NOTIFY_ID, builder.build());
                dlog("self-test posted via notification icon");
            } catch (Throwable t2) {
                Log.e(TAG, "self-test failed", t2);
            }
        }
    }

    /**
     * Show / update a per-dialog notification for an inbound OpenIM text message.
     * Caller must already filter: not outbound. Chat-open filtering is caller's job,
     * but this method still refuses only on hard permission / config failures.
     */
    public static void notifyIncoming(int account, long dialogId, String title, String body, int messageId) {
        Context ctx = ApplicationLoader.applicationContext;
        dlog("notifyIncoming account=" + account + " dialog=" + dialogId
                + " mid=" + messageId + " title=" + title);
        if (ctx == null || dialogId <= 0) {
            dlog("notify skip: no ctx/dialog");
            return;
        }
        if (!KgramServerConfig.USE_OPENIM_AUTH) {
            dlog("notify skip: OpenIM auth disabled");
            return;
        }
        ensureChannel(ctx);
        if (!canPost(ctx)) {
            dlog("notify skip: no permission / disabled — requesting");
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

            int notifyId = notifyIdFor(account, dialogId);
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
                    .setTicker(safeTitle + ": " + safeBody)
                    .setColor(0xff11acfa);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setPriority(NotificationCompat.PRIORITY_HIGH);
                builder.setDefaults(NotificationCompat.DEFAULT_ALL);
            }

            Notification n = builder.build();
            postRaw(ctx, notifyId, n);
            dlog("notify posted dialog=" + dialogId + " mid=" + messageId + " id=" + notifyId);
        } catch (Throwable t) {
            Log.e(TAG, "notify failed", t);
            FileLog.e("kgram notify failed");
            FileLog.e(t);
        }
    }

    private static void postRaw(Context ctx, int notifyId, Notification n) {
        // Prefer platform NotificationManager (survives some Compat OEM quirks).
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(notifyId, n);
                return;
            }
        } catch (SecurityException se) {
            Log.e(TAG, "nm.notify SecurityException — need POST_NOTIFICATIONS", se);
            requestPermissionIfNeeded();
            throw se;
        }
        NotificationManagerCompat.from(ctx).notify(notifyId, n);
    }

    public static void dumpState(Context ctx) {
        try {
            boolean enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled();
            boolean perm = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            String chInfo = "n/a";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
                    chInfo = ch == null ? "missing" : ("imp=" + ch.getImportance());
                }
            }
            dlog("state enabled=" + enabled + " perm=" + perm
                    + " channel=" + CHANNEL_ID + "/" + chInfo
                    + " stickyGranted=" + permissionGrantedSticky
                    + " sdk=" + Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            Log.e(TAG, "dumpState failed", t);
        }
    }

    public static void cancelDialog(int account, long dialogId) {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || dialogId <= 0) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(notifyIdFor(account, dialogId));
            }
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
