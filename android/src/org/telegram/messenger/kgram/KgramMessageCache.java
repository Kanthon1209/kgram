package org.telegram.messenger.kgram;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lightweight per-conversation recent-message cache so reopening a chat is not empty
 * while OpenIM history is still in flight. No polling — written on load/send/WS inject.
 */
public final class KgramMessageCache {
    private KgramMessageCache() {}

    private static final int MAX_PER_CONV = 300;
    private static final Object LOCK = new Object();

    private static File dir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), "kgram_msg_cache");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static File fileFor(Context ctx, String conversationID) {
        String safe = conversationID == null ? "unknown" : conversationID.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(dir(ctx), safe + ".json");
    }

    public static void save(Context ctx, String conversationID, List<OpenIMImClient.ChatMessage> msgs) {
        if (ctx == null || conversationID == null || conversationID.length() == 0 || msgs == null || msgs.isEmpty()) {
            return;
        }
        try {
            ArrayList<OpenIMImClient.ChatMessage> sorted = new ArrayList<>(msgs);
            sorted.sort(Comparator.comparingLong(m -> m.seq > 0 ? m.seq : m.sendTime));
            int from = Math.max(0, sorted.size() - MAX_PER_CONV);
            JSONArray arr = new JSONArray();
            for (int i = from; i < sorted.size(); i++) {
                OpenIMImClient.ChatMessage m = sorted.get(i);
                if (m == null) continue;
                JSONObject o = new JSONObject();
                o.put("clientMsgID", nullToEmpty(m.clientMsgID));
                o.put("serverMsgID", nullToEmpty(m.serverMsgID));
                o.put("sendID", nullToEmpty(m.sendID));
                o.put("recvID", nullToEmpty(m.recvID));
                o.put("senderNickname", nullToEmpty(m.senderNickname));
                o.put("text", nullToEmpty(m.text));
                o.put("sendTime", m.sendTime);
                o.put("contentType", m.contentType);
                o.put("seq", m.seq);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("conversationID", conversationID);
            root.put("updatedAt", System.currentTimeMillis());
            root.put("messages", arr);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (LOCK) {
                File f = fileFor(ctx, conversationID);
                File tmp = new File(f.getAbsolutePath() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                    fos.write(bytes);
                    fos.flush();
                }
                if (!tmp.renameTo(f)) {
                    // fallback overwrite
                    try (FileOutputStream fos = new FileOutputStream(f, false)) {
                        fos.write(bytes);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Merge incoming messages into existing cache (by seq/clientMsgID) and persist. */
    public static void mergeAndSave(Context ctx, String conversationID, List<OpenIMImClient.ChatMessage> incoming) {
        if (ctx == null || conversationID == null || incoming == null || incoming.isEmpty()) {
            return;
        }
        List<OpenIMImClient.ChatMessage> existing = load(ctx, conversationID);
        ArrayList<OpenIMImClient.ChatMessage> all = new ArrayList<>();
        if (existing != null) {
            all.addAll(existing);
        }
        all.addAll(incoming);
        // Dedupe preferring higher seq / later entries.
        java.util.LinkedHashMap<String, OpenIMImClient.ChatMessage> map = new java.util.LinkedHashMap<>();
        all.sort(Comparator.comparingLong(m -> m.seq > 0 ? m.seq : m.sendTime));
        for (OpenIMImClient.ChatMessage m : all) {
            if (m == null) continue;
            String key;
            if (m.seq > 0) {
                key = "q:" + m.seq;
            } else if (m.clientMsgID != null && m.clientMsgID.length() > 0) {
                key = "c:" + m.clientMsgID;
            } else if (m.serverMsgID != null && m.serverMsgID.length() > 0) {
                key = "s:" + m.serverMsgID;
            } else {
                key = "t:" + m.sendTime + ":" + nullToEmpty(m.text);
            }
            map.put(key, m);
        }
        save(ctx, conversationID, new ArrayList<>(map.values()));
    }

    public static List<OpenIMImClient.ChatMessage> load(Context ctx, String conversationID) {
        if (ctx == null || conversationID == null || conversationID.length() == 0) {
            return Collections.emptyList();
        }
        try {
            byte[] bytes;
            synchronized (LOCK) {
                File f = fileFor(ctx, conversationID);
                if (!f.exists() || f.length() == 0) {
                    return Collections.emptyList();
                }
                bytes = new byte[(int) f.length()];
                try (FileInputStream fis = new FileInputStream(f)) {
                    int off = 0;
                    while (off < bytes.length) {
                        int n = fis.read(bytes, off, bytes.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                }
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("messages");
            if (arr == null || arr.length() == 0) {
                return Collections.emptyList();
            }
            ArrayList<OpenIMImClient.ChatMessage> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                OpenIMImClient.ChatMessage m = new OpenIMImClient.ChatMessage();
                m.clientMsgID = o.optString("clientMsgID", null);
                m.serverMsgID = o.optString("serverMsgID", null);
                m.sendID = o.optString("sendID", null);
                m.recvID = o.optString("recvID", null);
                m.senderNickname = o.optString("senderNickname", null);
                m.text = o.optString("text", "");
                m.sendTime = o.optLong("sendTime", 0);
                m.contentType = o.optInt("contentType", OpenIMProtoLite.CONTENT_TEXT);
                m.seq = o.optLong("seq", 0);
                out.add(m);
            }
            return out;
        } catch (Throwable t) {
            FileLog.e(t);
            return Collections.emptyList();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
