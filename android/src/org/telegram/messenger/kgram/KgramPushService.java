package org.telegram.messenger.kgram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

/**
 * Lightweight foreground service that keeps the OpenIM WebSocket process alive
 * while the user backgrounds the app (Home). Required so inbound WS pushes can
 * still post {@link KgramNotifications} shade alerts.
 */
public class KgramPushService extends Service {
    public static final String CHANNEL_ID = "kgram_push_keepalive_v2";
    public static final int NOTIFY_ID = 70999;
    public static final String ACTION_ENSURE = "org.telegram.messenger.kgram.ENSURE_PUSH";
    public static final String ACTION_STOP = "org.telegram.messenger.kgram.STOP_PUSH";
    private static final String TAG = "kgram-fgs";

    private static volatile boolean started;

    public static void ensureStarted(Context ctx) {
        if (ctx == null || !KgramServerConfig.USE_OPENIM_AUTH) {
            return;
        }
        if (!KgramSession.hasSession(ctx)) {
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "ensureStarted skipped: no session");
            return;
        }
        try {
            Intent i = new Intent(ctx, KgramPushService.class);
            i.setAction(ACTION_ENSURE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "ensureStarted dispatched");
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void stop(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent i = new Intent(ctx, KgramPushService.class);
            i.setAction(ACTION_STOP);
            ctx.startService(i);
        } catch (Throwable ignore) {
        }
        try {
            ctx.stopService(new Intent(ctx, KgramPushService.class));
        } catch (Throwable t) {
            FileLog.e(t);
        }
        started = false;
    }

    public static boolean isStarted() {
        return started;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "onCreate");
        promoteForeground();
        bootBridge();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "onStartCommand action=" + action);
        if (ACTION_STOP.equals(action)) {
            started = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        promoteForeground();
        bootBridge();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        started = false;
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "onDestroy");
        super.onDestroy();
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx != null && KgramServerConfig.USE_OPENIM_AUTH && KgramSession.hasSession(ctx)) {
            try {
                ensureStarted(ctx);
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void promoteForeground() {
        ensureKeepAliveChannel();
        try {
            Notification n = buildKeepAliveNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ID, n);
            }
            started = true;
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "startForeground OK");
        } catch (Throwable t) {
            FileLog.e(t);
            // Still try to keep WS even if FGS notification fails.
            started = false;
        }
    }

    private void bootBridge() {
        try {
            Context ctx = getApplicationContext();
            if (ApplicationLoader.applicationContext == null) {
                ApplicationLoader.applicationContext = ctx;
            }
            // postInitApplication is idempotent (no-ops after first init).
            ApplicationLoader.postInitApplication();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            OpenIMWebSocket ws = OpenIMWebSocket.getInstance();
            if (started && ws.isRunning() && ws.isConnected()) {
                return;
            }
            KgramNotifications.ensureChannel(getApplicationContext());
            KgramTgBridge.ensurePushRuntime();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void ensureKeepAliveChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            try {
                nm.deleteNotificationChannel("kgram_push_keepalive");
            } catch (Throwable ignore) {
            }
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "连接保活", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("保持消息连接，可在系统设置中隐藏");
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private Notification buildKeepAliveNotification() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle("kgram")
                .setContentText("消息连接运行中")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi)
                .setShowWhen(false)
                .setColor(0xff11acfa)
                .build();
    }
}
