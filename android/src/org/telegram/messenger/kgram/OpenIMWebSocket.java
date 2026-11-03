package org.telegram.messenger.kgram;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenIM message-gateway WebSocket (sdkType=js JSON envelope).
 * Realtime path for push msgs (2001) and hasReadSeq notifications.
 * Ping / reconnect run on a dedicated HandlerThread so OEM main-looper
 * freezes in background cannot kill the keep-alive.
 */
public final class OpenIMWebSocket {
    public static final int WS_PUSH_MSG = 2001;
    public static final int WS_KICK = 2002;
    private static final String TAG = "kgram-ws";

    public interface Listener {
        void onPushMessages(OpenIMProtoLite.DecodedPush push);
        void onConnected();
        void onDisconnected(String reason);
    }

    private static final OpenIMWebSocket INSTANCE = new OpenIMWebSocket();

    public static OpenIMWebSocket getInstance() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private WebSocket socket;
    private Listener listener;
    private String userID;
    private String token;
    private boolean running;
    private int backoffMs = 1000;

    private HandlerThread workerThread;
    private Handler workerHandler;

    private final Runnable reconnectRunnable = this::connectInternal;
    private final Runnable pingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                WebSocket ws;
                synchronized (lock) {
                    ws = socket;
                }
                if (ws != null && ws.isOpen()) {
                    ws.sendText("{\"type\":\"ping\"}");
                }
            } catch (Throwable t) {
                elog("ping failed", t);
            }
            postWorker(this, 15000);
        }
    };


    private static void dlog(String msg) {
        if (BuildVars.LOGS_ENABLED) {
            Log.d(TAG, msg);
            FileLog.d(msg);
        }
    }

    private static void elog(String msg, Throwable tr) {
        if (BuildVars.LOGS_ENABLED) {
            if (tr != null) {
                Log.e(TAG, msg, tr);
            } else {
                Log.e(TAG, msg);
            }
        }
        if (tr != null) {
            FileLog.e(tr);
        } else {
            FileLog.e(msg);
        }
    }

    private OpenIMWebSocket() {}

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start(String userID, String imToken) {
        if (userID == null || userID.length() == 0 || imToken == null || imToken.length() == 0) {
            elog("start missing creds", null);
            return;
        }
        ensureWorker();
        synchronized (lock) {
            this.userID = userID;
            this.token = imToken;
            this.running = true;
            this.backoffMs = 1000;
        }
        cancelWorker(reconnectRunnable);
        postWorker(this::connectInternal, 0);
        dlog("start requested uid=" + userID);
    }

    public void stop() {
        running = false;
        generation.incrementAndGet();
        cancelWorker(reconnectRunnable);
        cancelWorker(pingRunnable);
        WebSocket prev;
        synchronized (lock) {
            prev = socket;
            socket = null;
        }
        if (prev != null) {
            try {
                prev.disconnect();
            } catch (Throwable ignore) {
            }
        }
        dlog("stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        synchronized (lock) {
            return socket != null && socket.isOpen();
        }
    }

    private void ensureWorker() {
        synchronized (lock) {
            if (workerThread != null && workerThread.isAlive() && workerHandler != null) {
                return;
            }
            workerThread = new HandlerThread("kgram-ws-worker");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
        }
    }

    private void postWorker(Runnable r, long delayMs) {
        ensureWorker();
        Handler h;
        synchronized (lock) {
            h = workerHandler;
        }
        if (h == null) {
            return;
        }
        if (delayMs <= 0) {
            h.post(r);
        } else {
            h.postDelayed(r, delayMs);
        }
    }

    private void cancelWorker(Runnable r) {
        Handler h;
        synchronized (lock) {
            h = workerHandler;
        }
        if (h != null) {
            h.removeCallbacks(r);
        }
    }

    private void connectInternal() {
        if (!running) {
            return;
        }
        final String uid;
        final String tok;
        synchronized (lock) {
            uid = userID;
            tok = token;
        }
        if (uid == null || tok == null) {
            return;
        }
        final int attempt = generation.incrementAndGet();
        WebSocket prev;
        synchronized (lock) {
            prev = socket;
            socket = null;
        }
        if (prev != null) {
            try {
                prev.disconnect();
            } catch (Throwable ignore) {
            }
        }
        try {
            String op = "kgram-" + UUID.randomUUID();
            String url = KgramServerConfig.IM_WS
                    + "/?sendID=" + enc(uid)
                    + "&token=" + enc(tok)
                    + "&platformID=" + KgramServerConfig.PLATFORM_ANDROID
                    + "&sdkType=js"
                    + "&isBackground=" + (isAppBackground() ? "true" : "false")
                    + "&isMsgResp=true"
                    + "&operationID=" + enc(op);
            dlog("connecting attempt=" + attempt + " bg=" + isAppBackground());
            WebSocket ws = new WebSocketFactory().setConnectionTimeout(12_000).createSocket(url);
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    postWorker(() -> {
                        if (!isCurrent(websocket, attempt)) {
                            websocket.disconnect();
                            return;
                        }
                        dlog("connected");
                        backoffMs = 1000;
                        cancelWorker(pingRunnable);
                        postWorker(pingRunnable, 5000);
                        Listener l = listener;
                        if (l != null) {
                            AndroidUtilities.runOnUIThread(l::onConnected);
                        }
                    }, 0);
                }

                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    handlePayload(websocket, attempt, text);
                }

                @Override
                public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                    if (binary == null) {
                        return;
                    }
                    String text;
                    try {
                        if (binary.length >= 2 && (binary[0] & 0xff) == 0x1f && (binary[1] & 0xff) == 0x8b) {
                            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(binary);
                            java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bais);
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = gis.read(buf)) > 0) {
                                baos.write(buf, 0, n);
                            }
                            gis.close();
                            text = baos.toString("UTF-8");
                        } else {
                            text = new String(binary, StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        elog("binary decode fail", e);
                        return;
                    }
                    handlePayload(websocket, attempt, text);
                }

                @Override
                public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame,
                                           WebSocketFrame clientCloseFrame, boolean closedByServer) {
                    scheduleReconnect(websocket, attempt, "disconnected closedByServer=" + closedByServer);
                }

                @Override
                public void onConnectError(WebSocket websocket, WebSocketException exception) {
                    scheduleReconnect(websocket, attempt, "connectError " + exception);
                }

                @Override
                public void onError(WebSocket websocket, WebSocketException exception) {
                    elog("ws error " + exception, null);
                }
            });
            synchronized (lock) {
                socket = ws;
            }
            ws.connectAsynchronously();
        } catch (Throwable t) {
            elog("create failed", t);
            scheduleReconnect(null, attempt, "create failed");
        }
    }

    private void handlePayload(WebSocket websocket, int attempt, String text) {
        if (text == null) {
            return;
        }
        final String payload = text.trim();
        if (payload.length() == 0) {
            return;
        }
        // Decode on worker; deliver to UI for bridge inject.
        postWorker(() -> {
            if (!isCurrent(websocket, attempt)) {
                return;
            }
            try {
                JSONObject obj = new JSONObject(payload);
                if ("pong".equals(obj.optString("type"))) {
                    return;
                }
                if (!obj.has("reqIdentifier") && obj.optInt("errCode", 0) == 0 && !obj.has("data")) {
                    dlog("handshake ok");
                    return;
                }
                int reqId = obj.optInt("reqIdentifier", 0);
                if (reqId == WS_KICK) {
                    elog("kicked", null);
                    scheduleReconnect(websocket, attempt, "kicked");
                    return;
                }
                if (reqId != WS_PUSH_MSG) {
                    return;
                }
                String dataB64 = obj.optString("data", null);
                if (dataB64 == null || dataB64.length() == 0) {
                    return;
                }
                byte[] raw = Base64.decode(dataB64, Base64.DEFAULT);
                OpenIMProtoLite.DecodedPush push = OpenIMProtoLite.decodePushMessages(raw);
                dlog("push msgs=" + push.messages.size() + " receipts=" + push.receipts.size());
                // Stay on worker — bridge prepares off-UI and only hops for TG model/notify.
                Listener l = listener;
                if (l != null) {
                    l.onPushMessages(push);
                }
            } catch (Throwable t) {
                elog("payload handle failed", t);
            }
        }, 0);
    }

    private void scheduleReconnect(WebSocket websocket, int attempt, String reason) {
        postWorker(() -> {
            if (!running || generation.get() != attempt) {
                return;
            }
            dlog("reconnect: " + reason);
            cancelWorker(pingRunnable);
            Listener l = listener;
            if (l != null) {
                AndroidUtilities.runOnUIThread(() -> l.onDisconnected(reason));
            }
            int delay = backoffMs;
            backoffMs = Math.min(30_000, Math.max(1000, backoffMs * 2));
            cancelWorker(reconnectRunnable);
            postWorker(reconnectRunnable, delay);
        }, 0);
    }

    private boolean isCurrent(WebSocket ws, int attempt) {
        synchronized (lock) {
            return running && socket == ws && generation.get() == attempt;
        }
    }

    private static boolean isAppBackground() {
        try {
            return !org.telegram.ui.Components.ForegroundDetector.getInstance().isForeground();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
