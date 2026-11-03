package org.telegram.messenger.kgram;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal protobuf decoder for OpenIM WS push payload ({@code sdkws.PushMessages}).
 * Enough to extract text MsgData + hasReadSeq notifications without a full protoc dependency.
 */
public final class OpenIMProtoLite {
    private OpenIMProtoLite() {}

    public static final int CONTENT_TEXT = 101;
    public static final int CONTENT_HAS_READ_RECEIPT = 2200;

    public static final class PushMsg {
        public String conversationID;
        public String sendID;
        public String recvID;
        public String clientMsgID;
        public String serverMsgID;
        public String senderNickname;
        public int sessionType;
        public int msgFrom;
        public int contentType;
        public String content;
        public long seq;
        public long sendTime;
        public long createTime;
        public int status;
        public boolean isRead;
        public boolean fromNotificationMap;

        public long bestSendTimeMs() {
            if (sendTime > 1_000_000_000_000L) return sendTime;
            if (createTime > 1_000_000_000_000L) return createTime;
            if (sendTime > 1_000_000_000L) return sendTime * 1000L; // sec -> ms
            if (createTime > 1_000_000_000L) return createTime * 1000L;
            return System.currentTimeMillis();
        }
    }

    public static final class ReadReceipt {
        public String conversationID;
        public String markAsReadUserID;
        public long hasReadSeq;
    }

    public static final class DecodedPush {
        public final List<PushMsg> messages = new ArrayList<>();
        public final List<ReadReceipt> receipts = new ArrayList<>();
    }

    public static DecodedPush decodePushMessages(byte[] data) {
        DecodedPush out = new DecodedPush();
        if (data == null || data.length == 0) {
            return out;
        }
        Reader root = new Reader(data, 0, data.length);
        while (root.hasMore()) {
            int tag = root.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (wire != 2) {
                root.skip(wire);
                continue;
            }
            byte[] entry = root.readBytes();
            boolean notif = field == 2;
            if (field != 1 && field != 2) {
                continue;
            }
            parseMapEntry(entry, notif, out);
        }
        return out;
    }

    private static void parseMapEntry(byte[] entry, boolean notification, DecodedPush out) {
        Reader r = new Reader(entry, 0, entry.length);
        String key = null;
        byte[] value = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (wire != 2) {
                r.skip(wire);
                continue;
            }
            if (field == 1) {
                key = r.readString();
            } else if (field == 2) {
                value = r.readBytes();
            } else {
                r.skip(wire);
            }
        }
        if (value == null) {
            return;
        }
        Reader pull = new Reader(value, 0, value.length);
        while (pull.hasMore()) {
            int tag = pull.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                PushMsg msg = parseMsgData(pull.readBytes());
                if (msg == null) {
                    continue;
                }
                if (key != null && (msg.conversationID == null || msg.conversationID.length() == 0)) {
                    msg.conversationID = key;
                }
                msg.fromNotificationMap = notification;
                if (isReadReceipt(msg)) {
                    ReadReceipt rr = toReceipt(msg);
                    if (rr != null) {
                        out.receipts.add(rr);
                    }
                } else if (msg.contentType == CONTENT_TEXT || (msg.content != null && msg.content.contains("\"content\""))) {
                    if (msg.contentType == 0) {
                        msg.contentType = CONTENT_TEXT;
                    }
                    out.messages.add(msg);
                } else if (!notification && msg.seq > 0) {
                    // Still surface unknown chat payloads with seq as text if content parses.
                    String text = extractText(msg.content);
                    if (text != null) {
                        msg.contentType = CONTENT_TEXT;
                        out.messages.add(msg);
                    }
                }
            } else {
                pull.skip(wire);
            }
        }
    }

    private static PushMsg parseMsgData(byte[] raw) {
        if (raw == null) {
            return null;
        }
        PushMsg m = new PushMsg();
        Reader r = new Reader(raw, 0, raw.length);
        while (r.hasMore()) {
            int tag = r.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 7;
            try {
                if (wire == 2) {
                    String s = r.readString();
                    switch (field) {
                        case 1: m.sendID = s; break;
                        case 2: m.recvID = s; break;
                        case 4: m.clientMsgID = s; break;
                        case 5: m.serverMsgID = s; break;
                        case 7: m.senderNickname = s; break;
                        case 12: m.content = s; break;
                        default: break;
                    }
                } else if (wire == 0) {
                    long v = r.readVarint64();
                    switch (field) {
                        case 9: m.sessionType = (int) v; break;
                        case 10: m.msgFrom = (int) v; break;
                        case 11: m.contentType = (int) v; break;
                        // MsgData: content=12(bytes), seq=14, sendTime=15, createTime=16, status=17, isRead=18
                        case 14: m.seq = v; break;
                        case 15: m.sendTime = v; break;
                        case 16: m.createTime = v; break;
                        case 17: m.status = (int) v; break;
                        case 18: m.isRead = v != 0; break;
                        default: break;
                    }
                } else {
                    r.skip(wire);
                }
            } catch (Exception e) {
                break;
            }
        }
        // OpenIM builds differ slightly: if sendTime looks like status (1..10) and createTime is epoch-ms, swap.
        if (m.sendTime > 0 && m.sendTime < 1000 && m.createTime > 1_000_000_000_000L) {
            m.status = (int) m.sendTime;
            m.sendTime = m.createTime;
        }
        // If seq omitted (proto3 zero) but status/sendTime slot held 1 for first msg — leave seq=0 for history sync.
        return m;
    }

    private static boolean isReadReceipt(PushMsg msg) {
        if (msg == null) {
            return false;
        }
        if (msg.contentType == CONTENT_HAS_READ_RECEIPT) {
            return true;
        }
        String c = msg.content;
        return c != null && (c.contains("hasReadSeq") || c.contains("markAsReadUserID"));
    }

    private static ReadReceipt toReceipt(PushMsg msg) {
        if (msg == null) {
            return null;
        }
        try {
            JSONObject d = null;
            if (msg.content != null && msg.content.length() > 0) {
                JSONObject root = new JSONObject(msg.content);
                String detail = root.optString("detail", "");
                if (detail == null || detail.length() == 0) {
                    detail = root.optString("jsonDetail", "");
                }
                if (detail != null && detail.length() > 0) {
                    String trimmed = detail.trim();
                    if (trimmed.startsWith("{")) {
                        d = new JSONObject(trimmed);
                    }
                }
                if (d == null) {
                    d = root;
                }
            }
            if (d == null) {
                d = new JSONObject();
            }
            ReadReceipt rr = new ReadReceipt();
            rr.conversationID = firstNonEmpty(
                    d.optString("conversationID", null),
                    d.optString("ConversationID", null),
                    msg.conversationID);
            rr.markAsReadUserID = firstNonEmpty(
                    d.optString("markAsReadUserID", null),
                    d.optString("MarkAsReadUserID", null),
                    msg.sendID);
            rr.hasReadSeq = d.optLong("hasReadSeq", 0);
            if (rr.hasReadSeq <= 0) {
                rr.hasReadSeq = d.optLong("HasReadSeq", 0);
            }
            if (rr.hasReadSeq <= 0) {
                org.json.JSONArray arr = d.optJSONArray("seqs");
                if (arr == null) arr = d.optJSONArray("Seqs");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        long s = arr.optLong(i);
                        if (s > rr.hasReadSeq) rr.hasReadSeq = s;
                    }
                }
            }
            // Notification map key is often n_a_b — prefer si_ from tips; else rewrite n_ → si_.
            if (rr.conversationID != null && rr.conversationID.startsWith("n_")) {
                rr.conversationID = "si_" + rr.conversationID.substring(2);
            }
            if ((rr.conversationID == null || rr.conversationID.length() == 0) && rr.markAsReadUserID != null
                    && msg.recvID != null && msg.recvID.length() > 0) {
                // Last resort: synthesize single-chat id from reader + receiver.
                String a = rr.markAsReadUserID;
                String b = msg.recvID.equals(a) ? msg.sendID : msg.recvID;
                if (b != null && b.length() > 0) {
                    if (a.compareTo(b) <= 0) {
                        rr.conversationID = "si_" + a + "_" + b;
                    } else {
                        rr.conversationID = "si_" + b + "_" + a;
                    }
                }
            }
            if (rr.conversationID == null || rr.conversationID.length() == 0 || rr.hasReadSeq <= 0) {
                return null;
            }
            return rr;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && v.length() > 0 && !"null".equals(v)) return v;
        }
        return null;
    }

    public static String extractText(String contentJson) {
        if (contentJson == null || contentJson.length() == 0) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(contentJson);
            String t = o.optString("content", null);
            return t;
        } catch (Exception e) {
            return contentJson;
        }
    }

    /** Peer userID from {@code si_a_b} / {@code n_a_b} given my userID. */
    public static String peerFromConversationID(String conversationID, String me) {
        if (conversationID == null || me == null) {
            return null;
        }
        String id = conversationID;
        if (id.startsWith("n_")) {
            id = "si_" + id.substring(2);
        }
        if (!id.startsWith("si_")) {
            return null;
        }
        String rest = id.substring(3);
        int idx = rest.indexOf('_');
        if (idx <= 0) {
            return null;
        }
        String a = rest.substring(0, idx);
        String b = rest.substring(idx + 1);
        if (me.equals(a)) {
            return b;
        }
        if (me.equals(b)) {
            return a;
        }
        return null;
    }

    private static final class Reader {
        final byte[] buf;
        int pos;
        final int end;

        Reader(byte[] buf, int pos, int end) {
            this.buf = buf;
            this.pos = pos;
            this.end = end;
        }

        boolean hasMore() {
            return pos < end;
        }

        int readVarint32() {
            return (int) readVarint64();
        }

        long readVarint64() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                int b = buf[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException("varint too long");
                }
            }
            throw new IllegalStateException("truncated varint");
        }

        byte[] readBytes() {
            int len = readVarint32();
            if (len < 0 || pos + len > end) {
                throw new IllegalStateException("bad len");
            }
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        String readString() {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        void skip(int wire) {
            switch (wire) {
                case 0:
                    readVarint64();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 2: {
                    int len = readVarint32();
                    pos += len;
                    break;
                }
                case 5:
                    pos += 4;
                    break;
                default:
                    throw new IllegalStateException("wire " + wire);
            }
        }
    }
}
