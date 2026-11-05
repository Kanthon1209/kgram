package org.telegram.messenger.kgram;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenIM Server REST helpers for demo: conversations, friends, history, send (admin).
 */
public final class OpenIMImClient {
    private OpenIMImClient() {}

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4);
    private static volatile String cachedAdminToken;
    private static volatile long cachedAdminAt;

    public interface Callback<T> {
        void onDone(ApiResult<T> result);
    }

    public static final class ApiResult<T> {
        public final int errCode;
        public final String errMsg;
        public final String errDlt;
        public final T data;
        public final String raw;

        public ApiResult(int errCode, String errMsg, String errDlt, T data, String raw) {
            this.errCode = errCode;
            this.errMsg = errMsg;
            this.errDlt = errDlt;
            this.data = data;
            this.raw = raw;
        }

        public boolean ok() {
            return errCode == 0;
        }
    }

    public static final class Conversation {
        public String conversationID;
        public String userID;
        public String showName;
        public String faceURL;
        public String latestMsgContent;
        public long latestMsgSendTime;
        public long latestMsgSeq;
        public String latestMsgSendID;
        public int unreadCount;
        /** Own hasReadSeq for this conversation when returned by seq APIs. */
        public long hasReadSeq;
        public long maxSeq;
    }

    /** hasReadSeq + maxSeq for one conversation. */
    public static final class SeqInfo {
        public String conversationID;
        public long maxSeq;
        public long hasReadSeq;
        public long maxSeqTime;
    }

    public static final class Friend {
        public String userID;
        public String nickname;
        public String faceURL;
        public String remark;
    }

    public static final class ChatMessage {
        public String clientMsgID;
        public String serverMsgID;
        public String sendID;
        public String recvID;
        public String senderNickname;
        public String text;
        public long sendTime;
        public int contentType;
        public long seq;
    }

    public static final class UserInfo {
        public String userID;
        public String nickname;
        public String faceURL;
    }

    public static String singleConversationID(String a, String b) {
        List<String> ids = new ArrayList<>();
        ids.add(a);
        ids.add(b);
        Collections.sort(ids);
        return "si_" + ids.get(0) + "_" + ids.get(1);
    }

    public static void getConversationsAsync(String ownerUserID, String imToken, Callback<List<Conversation>> cb) {
        EXEC.execute(() -> {
            try {
                // Prefer sorted conversation list (same data Dialogs pull-to-refresh should hit).
                ApiResult<List<Conversation>> sorted = getSortedConversations(ownerUserID, imToken);
                if (sorted.ok() && sorted.data != null) {
                    cb.onDone(sorted);
                    return;
                }
                cb.onDone(getConversations(ownerUserID, imToken));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    public static void getFriendsAsync(String userID, String imToken, Callback<List<Friend>> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(getFriends(userID, imToken));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    public static void searchHistoryAsync(String sendID, String recvID, String imToken, Callback<List<ChatMessage>> cb) {
        searchHistoryAsync(sendID, recvID, imToken, 0, 100, cb);
    }

    /**
     * @param beforeSeq exclusive upper bound (OpenIM seq). 0 = latest window ending at maxSeq.
     * @param limit max messages to pull (clamped 1..200).
     */
    public static void searchHistoryAsync(String sendID, String recvID, String imToken, long beforeSeq, int limit,
                                          Callback<List<ChatMessage>> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(searchHistory(sendID, recvID, imToken, beforeSeq, limit));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    public static void sendTextAsync(String sendID, String recvID, String senderNickname, String text, String clientMsgID, Callback<JSONObject> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(sendText(sendID, recvID, senderNickname, text, clientMsgID));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    public static void sendTextAsync(String sendID, String recvID, String senderNickname, String text, Callback<JSONObject> cb) {
        sendTextAsync(sendID, recvID, senderNickname, text, null, cb);
    }

    public static void ensureFriendAsync(String ownerUserID, String friendUserID, Callback<Boolean> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(ensureFriend(ownerUserID, friendUserID));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", false, null));
            }
        });
    }

    public static void getUsersInfoAsync(List<String> userIDs, String imToken, Callback<List<UserInfo>> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(getUsersInfo(userIDs, imToken));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    public static void getAllUsersAsync(Callback<List<UserInfo>> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(getAllUsers());
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    public static void getHasReadAndMaxSeqAsync(String ownerUserID, List<String> conversationIDs, String imToken,
                                               Callback<Map<String, SeqInfo>> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(getHasReadAndMaxSeq(ownerUserID, conversationIDs, imToken));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    public static void markConversationAsReadAsync(String userID, String conversationID, long hasReadSeq, String imToken,
                                                   Callback<Boolean> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(markConversationAsRead(userID, conversationID, hasReadSeq, imToken));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", false, null));
            }
        });
    }

    /** Peer hasReadSeq for a single chat (query peer's own read cursor). */
    public static void getPeerHasReadSeqAsync(String peerUserID, String conversationID, String imToken,
                                             Callback<Long> cb) {
        EXEC.execute(() -> {
            try {
                List<String> ids = new ArrayList<>();
                ids.add(conversationID);
                ApiResult<Map<String, SeqInfo>> res = getHasReadAndMaxSeq(peerUserID, ids, imToken);
                // User token may be rejected when ownerUserID != token subject — retry as admin.
                if (res == null || !res.ok() || res.data == null || !res.data.containsKey(conversationID)) {
                    try {
                        String admin = getAdminToken();
                        ApiResult<Map<String, SeqInfo>> adminRes = getHasReadAndMaxSeq(peerUserID, ids, admin);
                        if (adminRes != null && adminRes.ok()) {
                            res = adminRes;
                        }
                    } catch (Exception ignore) {
                    }
                }
                long has = 0;
                if (res != null && res.ok() && res.data != null && res.data.containsKey(conversationID)) {
                    has = res.data.get(conversationID).hasReadSeq;
                }
                int code = res == null ? -1 : res.errCode;
                String msg = res == null ? "null" : res.errMsg;
                String dlt = res == null ? "" : res.errDlt;
                String raw = res == null ? null : res.raw;
                cb.onDone(new ApiResult<>(code, msg, dlt, has, raw));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", 0L, null));
            }
        });
    }

    public static ApiResult<List<Conversation>> getSortedConversations(String ownerUserID, String imToken) throws Exception {
        JSONObject pagination = new JSONObject();
        pagination.put("pageNumber", 1);
        pagination.put("showNumber", 200);
        JSONObject body = new JSONObject();
        body.put("userID", ownerUserID);
        body.put("pagination", pagination);
        Raw raw = post(KgramServerConfig.IM_API + "/conversation/get_sorted_conversation_list", body, imToken);
        if (raw.errCode != 0) {
            // Some deployments only expose get_all_conversations.
            return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, null, raw.raw);
        }
        List<Conversation> list = parseConversationArray(raw, ownerUserID);
        return new ApiResult<>(0, "", "", list, raw.raw);
    }

    private static void applyLatestMsg(Conversation c, JSONObject lm) {
        if (c == null || lm == null) {
            return;
        }
        c.latestMsgContent = extractText(lm);
        if (c.latestMsgSendTime <= 0) {
            c.latestMsgSendTime = lm.optLong("sendTime");
        }
        if (c.latestMsgSeq <= 0) {
            c.latestMsgSeq = lm.optLong("seq");
        }
        if (c.latestMsgSendID == null || c.latestMsgSendID.length() == 0) {
            c.latestMsgSendID = lm.optString("sendID");
        }
    }

    private static List<Conversation> parseConversationArray(Raw raw, String ownerUserID) throws Exception {
        List<Conversation> list = new ArrayList<>();
        if (raw == null || raw.data == null) {
            return list;
        }
        JSONArray arr = raw.data.optJSONArray("conversations");
        if (arr == null) {
            arr = raw.data.optJSONArray("conversationElems");
        }
        if (arr == null) {
            Object v = raw.data.opt("value");
            if (v instanceof JSONArray) {
                arr = (JSONArray) v;
            }
        }
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Conversation c = new Conversation();
            c.conversationID = o.optString("conversationID");
            c.userID = o.optString("userID");
            c.showName = o.optString("showName");
            c.faceURL = o.optString("faceURL");
            c.unreadCount = o.optInt("unreadCount");
            c.latestMsgSendTime = o.optLong("latestMsgSendTime");
            c.latestMsgSeq = o.optLong("maxSeq");
            Object latest = o.opt("latestMsg");
            if (latest instanceof JSONObject) {
                applyLatestMsg(c, (JSONObject) latest);
            } else if (latest instanceof String) {
                String s = (String) latest;
                if (s.startsWith("{")) {
                    try {
                        applyLatestMsg(c, new JSONObject(s));
                    } catch (Exception ignore) {
                        c.latestMsgContent = s;
                    }
                } else {
                    c.latestMsgContent = s;
                }
            }
            if ((c.userID == null || c.userID.length() == 0) && c.conversationID != null && c.conversationID.startsWith("si_")) {
                String rest = c.conversationID.substring(3);
                int idx = rest.indexOf('_');
                if (idx > 0 && idx < rest.length() - 1) {
                    String a = rest.substring(0, idx);
                    String b = rest.substring(idx + 1);
                    if (a.equals(ownerUserID)) {
                        c.userID = b;
                    } else if (b.equals(ownerUserID)) {
                        c.userID = a;
                    } else {
                        c.userID = b;
                    }
                }
            }
            if (c.showName == null || c.showName.length() == 0) {
                c.showName = c.userID;
            }
            list.add(c);
        }
        return list;
    }

    public static ApiResult<List<Conversation>> getConversations(String ownerUserID, String imToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("ownerUserID", ownerUserID);
        Raw raw = post(KgramServerConfig.IM_API + "/conversation/get_all_conversations", body, imToken);
        if (raw.errCode != 0) {
            return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, null, raw.raw);
        }
        List<Conversation> list = new ArrayList<>();
        if (raw.data != null) {
            JSONArray arr = raw.data.optJSONArray("conversations");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Conversation c = new Conversation();
                    c.conversationID = o.optString("conversationID");
                    c.userID = o.optString("userID");
                    c.showName = o.optString("showName");
                    c.faceURL = o.optString("faceURL");
                    c.unreadCount = o.optInt("unreadCount");
                    c.latestMsgSendTime = o.optLong("latestMsgSendTime");
                    if (c.latestMsgSendTime <= 0) {
                        c.latestMsgSendTime = o.optLong("latestMsgSendTime", 0);
                    }
                    Object latest = o.opt("latestMsg");
                    if (latest instanceof JSONObject) {
                        JSONObject lm = (JSONObject) latest;
                        applyLatestMsg(c, lm);
                    } else if (latest instanceof String) {
                        String s = (String) latest;
                        if (s.startsWith("{")) {
                            try {
                                applyLatestMsg(c, new JSONObject(s));
                            } catch (Exception ignore) {
                                c.latestMsgContent = s;
                            }
                        } else {
                            c.latestMsgContent = s;
                        }
                    }
                    if (c.latestMsgSeq <= 0) {
                        c.latestMsgSeq = o.optLong("maxSeq");
                    }
                    // single chat: si_uidA_uidB — recover peer if userID blank
                    if ((c.userID == null || c.userID.length() == 0) && c.conversationID != null && c.conversationID.startsWith("si_")) {
                        String rest = c.conversationID.substring(3);
                        int idx = rest.indexOf('_');
                        if (idx > 0 && idx < rest.length() - 1) {
                            String a = rest.substring(0, idx);
                            String b = rest.substring(idx + 1);
                            if (a.equals(ownerUserID)) {
                                c.userID = b;
                            } else if (b.equals(ownerUserID)) {
                                c.userID = a;
                            } else {
                                c.userID = b; // fallback
                            }
                        }
                    }
                    if (c.showName == null || c.showName.length() == 0) {
                        c.showName = c.userID;
                    }
                    list.add(c);
                }
            }
        }
        return new ApiResult<>(0, "", "", list, raw.raw);
    }

    public static ApiResult<List<Friend>> getFriends(String userID, String imToken) throws Exception {
        JSONObject pagination = new JSONObject();
        pagination.put("pageNumber", 1);
        pagination.put("showNumber", 200);
        JSONObject body = new JSONObject();
        body.put("userID", userID);
        body.put("pagination", pagination);
        Raw raw = post(KgramServerConfig.IM_API + "/friend/get_friend_list", body, imToken);
        if (raw.errCode != 0) {
            return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, null, raw.raw);
        }
        List<Friend> list = new ArrayList<>();
        if (raw.data != null) {
            JSONArray arr = raw.data.optJSONArray("friendsInfo");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Friend f = new Friend();
                    f.remark = o.optString("remark");
                    JSONObject fu = o.optJSONObject("friendUser");
                    if (fu != null) {
                        f.userID = fu.optString("userID");
                        f.nickname = fu.optString("nickname");
                        f.faceURL = fu.optString("faceURL");
                    }
                    list.add(f);
                }
            }
        }
        return new ApiResult<>(0, "", "", list, raw.raw);
    }

    public static ApiResult<List<ChatMessage>> searchHistory(String sendID, String recvID, String imToken) throws Exception {
        return searchHistory(sendID, recvID, imToken, 0, 100);
    }

    /**
     * Pull conversation history by seq.
     * beforeSeq==0 → latest window ending at server maxSeq.
     * beforeSeq>0 → older page with end = beforeSeq-1 (exclusive upper bound of current UI oldest).
     */
    public static ApiResult<List<ChatMessage>> searchHistory(String sendID, String recvID, String imToken,
                                                            long beforeSeq, int limit) throws Exception {
        // Bidirectional history via conversation pull (search_msg is one-direction only).
        String convID = singleConversationID(sendID, recvID);
        int lim = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 200));
        JSONArray conversationIDs = new JSONArray();
        conversationIDs.put(convID);
        JSONObject seqBody = new JSONObject();
        seqBody.put("userID", sendID);
        seqBody.put("conversationIDs", conversationIDs);
        Raw seqRaw = post(KgramServerConfig.IM_API + "/msg/get_conversations_has_read_and_max_seq", seqBody, imToken);
        if (seqRaw.errCode != 0) {
            return new ApiResult<>(seqRaw.errCode, seqRaw.errMsg, seqRaw.errDlt, null, seqRaw.raw);
        }
        long maxSeq = 0;
        if (seqRaw.data != null) {
            JSONObject seqs = seqRaw.data.optJSONObject("seqs");
            if (seqs != null) {
                JSONObject info = seqs.optJSONObject(convID);
                if (info != null) {
                    maxSeq = info.optLong("maxSeq");
                }
            }
        }
        if (maxSeq <= 0) {
            return new ApiResult<>(0, "", "", new ArrayList<ChatMessage>(), seqRaw.raw);
        }
        long end;
        if (beforeSeq > 0) {
            end = Math.min(maxSeq, beforeSeq - 1);
        } else {
            end = maxSeq;
        }
        if (end < 1) {
            return new ApiResult<>(0, "", "", new ArrayList<ChatMessage>(), seqRaw.raw);
        }
        long begin = Math.max(1, end - lim + 1);
        JSONObject range = new JSONObject();
        range.put("conversationID", convID);
        range.put("begin", begin);
        range.put("end", end);
        range.put("num", (int) Math.min(lim, end - begin + 1));
        JSONArray ranges = new JSONArray();
        ranges.put(range);
        JSONObject pullBody = new JSONObject();
        pullBody.put("userID", sendID);
        pullBody.put("seqRanges", ranges);
        Raw raw = post(KgramServerConfig.IM_API + "/msg/pull_msg_by_seq", pullBody, imToken);
        if (raw.errCode != 0) {
            return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, null, raw.raw);
        }
        List<ChatMessage> list = new ArrayList<>();
        if (raw.data != null) {
            JSONObject msgsMap = raw.data.optJSONObject("msgs");
            if (msgsMap != null) {
                JSONObject bucket = msgsMap.optJSONObject(convID);
                if (bucket != null) {
                    JSONArray arr = bucket.optJSONArray("Msgs");
                    if (arr == null) {
                        arr = bucket.optJSONArray("msgs");
                    }
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            int contentType = o.optInt("contentType");
                            if (contentType != 101) {
                                continue; // text only for demo
                            }
                            ChatMessage m = new ChatMessage();
                            m.clientMsgID = o.optString("clientMsgID");
                            m.serverMsgID = o.optString("serverMsgID");
                            m.sendID = o.optString("sendID");
                            m.recvID = o.optString("recvID");
                            m.senderNickname = o.optString("senderNickname");
                            m.sendTime = o.optLong("sendTime");
                            m.contentType = contentType;
                            m.seq = o.optLong("seq");
                            m.text = extractPullContent(o.opt("content"));
                            list.add(m);
                        }
                    }
                }
            }
        }
        return new ApiResult<>(0, "", "", list, raw.raw);
    }

    private static String extractPullContent(Object content) {
        if (content == null || content == JSONObject.NULL) {
            return "";
        }
        if (content instanceof JSONObject) {
            return ((JSONObject) content).optString("content", content.toString());
        }
        String s = String.valueOf(content);
        // pull_msg_by_seq often returns base64(JSON)
        try {
            byte[] decoded = Base64.decode(s, Base64.DEFAULT);
            String json = new String(decoded, StandardCharsets.UTF_8);
            if (json.startsWith("{")) {
                return new JSONObject(json).optString("content", json);
            }
            return json;
        } catch (Exception ignore) {
            return extractContentField(content);
        }
    }

    public static ApiResult<JSONObject> sendText(String sendID, String recvID, String senderNickname, String text) throws Exception {
        return sendText(sendID, recvID, senderNickname, text, null);
    }

    public static ApiResult<JSONObject> sendText(String sendID, String recvID, String senderNickname, String text, String clientMsgID) throws Exception {
        String admin = getAdminToken();
        if (clientMsgID == null || clientMsgID.length() == 0) {
            clientMsgID = UUID.randomUUID().toString();
        }
        JSONObject content = new JSONObject();
        content.put("content", text);
        JSONObject body = new JSONObject();
        body.put("sendID", sendID);
        body.put("recvID", recvID);
        body.put("groupID", "");
        body.put("senderNickname", senderNickname != null ? senderNickname : sendID);
        body.put("senderFaceURL", "");
        body.put("senderPlatformID", KgramServerConfig.PLATFORM_ANDROID);
        body.put("content", content);
        body.put("contentType", 101);
        body.put("sessionType", 1);
        body.put("isOnlineOnly", false);
        body.put("notOfflinePush", false);
        body.put("clientMsgID", clientMsgID);
        Raw raw = post(KgramServerConfig.IM_API + "/msg/send_msg", body, admin);
        // Ensure caller can always read back the clientMsgID we sent.
        if (raw.data == null) {
            raw.data = new JSONObject();
        }
        try {
            if (!raw.data.has("clientMsgID") || raw.data.optString("clientMsgID").length() == 0) {
                raw.data.put("clientMsgID", clientMsgID);
            }
        } catch (Exception ignore) {
        }
        return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, raw.data, raw.raw);
    }

    public static ApiResult<Boolean> ensureFriend(String ownerUserID, String friendUserID) throws Exception {
        String admin = getAdminToken();
        JSONArray ids = new JSONArray();
        ids.put(friendUserID);
        JSONObject body = new JSONObject();
        body.put("ownerUserID", ownerUserID);
        body.put("friendUserIDs", ids);
        Raw raw = post(KgramServerConfig.IM_API + "/friend/import_friend", body, admin);
        // already friends may still return 0
        return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, raw.errCode == 0, raw.raw);
    }

    public static ApiResult<List<UserInfo>> getUsersInfo(List<String> userIDs, String imToken) throws Exception {
        JSONArray arr = new JSONArray();
        for (String id : userIDs) {
            arr.put(id);
        }
        JSONObject body = new JSONObject();
        body.put("userIDs", arr);
        Raw raw = post(KgramServerConfig.IM_API + "/user/get_users_info", body, imToken);
        if (raw.errCode != 0) {
            return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, null, raw.raw);
        }
        List<UserInfo> list = new ArrayList<>();
        if (raw.data != null) {
            JSONArray users = raw.data.optJSONArray("usersInfo");
            if (users != null) {
                for (int i = 0; i < users.length(); i++) {
                    JSONObject o = users.getJSONObject(i);
                    UserInfo u = new UserInfo();
                    u.userID = o.optString("userID");
                    u.nickname = o.optString("nickname");
                    u.faceURL = o.optString("faceURL");
                    list.add(u);
                }
            }
        }
        return new ApiResult<>(0, "", "", list, raw.raw);
    }

    /** Demo: list registered IM users via admin so contacts are not empty. */
    public static ApiResult<List<UserInfo>> getAllUsers() throws Exception {
        String admin = getAdminToken();
        JSONObject pagination = new JSONObject();
        pagination.put("pageNumber", 1);
        pagination.put("showNumber", 200);
        JSONObject body = new JSONObject();
        body.put("pagination", pagination);
        Raw raw = post(KgramServerConfig.IM_API + "/user/get_users", body, admin);
        if (raw.errCode != 0) {
            return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, null, raw.raw);
        }
        List<UserInfo> list = new ArrayList<>();
        if (raw.data != null) {
            JSONArray users = raw.data.optJSONArray("users");
            if (users != null) {
                for (int i = 0; i < users.length(); i++) {
                    JSONObject o = users.getJSONObject(i);
                    UserInfo u = new UserInfo();
                    u.userID = o.optString("userID");
                    u.nickname = o.optString("nickname");
                    u.faceURL = o.optString("faceURL");
                    if (u.userID != null && u.userID.length() > 0) {
                        list.add(u);
                    }
                }
            }
        }
        return new ApiResult<>(0, "", "", list, raw.raw);
    }

    public static ApiResult<Map<String, SeqInfo>> getHasReadAndMaxSeq(String ownerUserID, List<String> conversationIDs, String imToken) throws Exception {
        JSONArray arr = new JSONArray();
        if (conversationIDs != null) {
            for (String id : conversationIDs) {
                if (id != null && id.length() > 0) {
                    arr.put(id);
                }
            }
        }
        JSONObject body = new JSONObject();
        body.put("userID", ownerUserID);
        body.put("conversationIDs", arr);
        Raw raw = post(KgramServerConfig.IM_API + "/msg/get_conversations_has_read_and_max_seq", body, imToken);
        if (raw.errCode != 0) {
            return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, null, raw.raw);
        }
        Map<String, SeqInfo> map = new HashMap<>();
        if (raw.data != null) {
            JSONObject seqs = raw.data.optJSONObject("seqs");
            if (seqs != null) {
                JSONArray names = seqs.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String cid = names.getString(i);
                        JSONObject info = seqs.optJSONObject(cid);
                        if (info == null) continue;
                        SeqInfo s = new SeqInfo();
                        s.conversationID = cid;
                        s.maxSeq = info.optLong("maxSeq");
                        s.hasReadSeq = info.optLong("hasReadSeq");
                        s.maxSeqTime = info.optLong("maxSeqTime");
                        map.put(cid, s);
                    }
                }
            }
        }
        return new ApiResult<>(0, "", "", map, raw.raw);
    }

    public static ApiResult<Boolean> markConversationAsRead(String userID, String conversationID, long hasReadSeq, String imToken) throws Exception {
        if (userID == null || conversationID == null || hasReadSeq <= 0) {
            return new ApiResult<>(-1, "bad args", "", false, null);
        }
        JSONObject body = new JSONObject();
        body.put("userID", userID);
        body.put("conversationID", conversationID);
        body.put("hasReadSeq", hasReadSeq);
        Raw raw = post(KgramServerConfig.IM_API + "/msg/mark_conversation_as_read", body, imToken);
        return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, raw.errCode == 0, raw.raw);
    }


    public static void updateUserInfoAsync(String userID, String nickname, String faceURL, String imToken,
                                           Callback<Boolean> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(updateUserInfo(userID, nickname, faceURL, imToken));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", false, null));
            }
        });
    }

    /** POST /user/update_user_info — nickname and/or faceURL (null keeps server value if omitted empty). */
    public static ApiResult<Boolean> updateUserInfo(String userID, String nickname, String faceURL, String imToken) throws Exception {
        JSONObject userInfo = new JSONObject();
        userInfo.put("userID", userID);
        if (nickname != null) {
            userInfo.put("nickname", nickname);
        }
        if (faceURL != null) {
            userInfo.put("faceURL", faceURL);
        }
        JSONObject body = new JSONObject();
        body.put("userInfo", userInfo);
        Raw raw = post(KgramServerConfig.IM_API + "/user/update_user_info", body, imToken);
        return new ApiResult<>(raw.errCode, raw.errMsg, raw.errDlt, raw.errCode == 0, raw.raw);
    }

    public static void uploadAvatarAsync(File file, String userID, String imToken, Callback<String> cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(uploadAvatar(file, userID, imToken));
            } catch (Exception e) {
                cb.onDone(new ApiResult<>(-1, e.getMessage(), "", null, null));
            }
        });
    }

    /**
     * Upload avatar via OpenIM object form-data API, returns public faceURL.
     * Falls back to single-part multipart if form-data is unavailable.
     */
    public static ApiResult<String> uploadAvatar(File file, String userID, String imToken) throws Exception {
        if (file == null || !file.exists() || file.length() <= 0) {
            return new ApiResult<>(-1, "missing file", "", null, null);
        }
        String name = (userID != null ? userID : "user") + "/avatar_" + System.currentTimeMillis() + ".jpg";
        String contentType = "image/jpeg";
        ApiResult<String> form = uploadViaFormData(file, name, contentType, imToken);
        if (form.ok() && form.data != null && form.data.length() > 0) {
            return form;
        }
        ApiResult<String> multi = uploadViaMultipart(file, name, contentType, imToken);
        if (multi.ok()) {
            return multi;
        }
        return form.errCode != 0 ? form : multi;
    }

    private static ApiResult<String> uploadViaFormData(File file, String name, String contentType, String imToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", name);
        body.put("size", file.length());
        body.put("contentType", contentType);
        Raw init = post(KgramServerConfig.IM_API + "/object/initiate_form_data", body, imToken);
        if (init.errCode != 0 || init.data == null) {
            return new ApiResult<>(init.errCode, init.errMsg, init.errDlt, null, init.raw);
        }
        String id = init.data.optString("id");
        String uploadUrl = init.data.optString("url");
        String fileField = init.data.optString("file", "file");
        JSONObject formData = init.data.optJSONObject("formData");
        if (uploadUrl == null || uploadUrl.length() == 0) {
            return new ApiResult<>(-1, "no form upload url", init.errDlt, null, init.raw);
        }
        String boundary = "----kgram" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (init.data.optJSONArray("header") != null) {
                // header is repeated KeyValues in proto; JSON often [{key,value}] or map — skip if unknown shape
            }
            OutputStream os = conn.getOutputStream();
            if (formData != null) {
                JSONArray keys = formData.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String k = keys.getString(i);
                        String v = formData.optString(k);
                        writeFormField(os, boundary, k, v);
                    }
                }
            }
            writeFormFile(os, boundary, fileField, file.getName(), contentType, file);
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp = is != null ? readAll(is) : "";
            boolean okCode = code >= 200 && code < 300;
            JSONArray successCodes = init.data.optJSONArray("successCodes");
            if (successCodes != null) {
                okCode = false;
                for (int i = 0; i < successCodes.length(); i++) {
                    if (successCodes.optInt(i) == code) {
                        okCode = true;
                        break;
                    }
                }
            }
            if (!okCode) {
                return new ApiResult<>(code, "form upload HTTP " + code, resp, null, resp);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        JSONObject completeBody = new JSONObject();
        completeBody.put("id", id);
        Raw done = post(KgramServerConfig.IM_API + "/object/complete_form_data", completeBody, imToken);
        if (done.errCode != 0 || done.data == null) {
            return new ApiResult<>(done.errCode, done.errMsg, done.errDlt, null, done.raw);
        }
        String url = done.data.optString("url");
        return new ApiResult<>(0, "", "", url, done.raw);
    }

    private static ApiResult<String> uploadViaMultipart(File file, String name, String contentType, String imToken) throws Exception {
        byte[] bytes = readFileBytes(file);
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] dig = md.digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : dig) hex.append(String.format("%02x", b));
        String partMd5 = hex.toString(); // hex(md5(content)) for the single part
        // OpenIM initiate hash = md5(join(partMd5s, ","))
        MessageDigest md2 = MessageDigest.getInstance("MD5");
        byte[] dig2 = md2.digest(partMd5.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex2 = new StringBuilder();
        for (byte b : dig2) hex2.append(String.format("%02x", b));
        String hash = hex2.toString();
        long partSize = Math.max(bytes.length, 1);
        JSONObject initBody = new JSONObject();
        initBody.put("hash", hash);
        initBody.put("size", (long) bytes.length);
        initBody.put("partSize", partSize);
        initBody.put("maxParts", 1);
        initBody.put("cause", "avatar");
        initBody.put("name", name);
        initBody.put("contentType", contentType);
        Raw init = post(KgramServerConfig.IM_API + "/object/initiate_multipart_upload", initBody, imToken);
        if (init.errCode != 0 || init.data == null) {
            return new ApiResult<>(init.errCode, init.errMsg, init.errDlt, null, init.raw);
        }
        String existing = init.data.optString("url");
        JSONObject upload = init.data.optJSONObject("upload");
        if (upload == null) {
            return new ApiResult<>(0, "", "", existing, init.raw);
        }
        String uploadID = upload.optString("uploadID");
        // Prefer sign from initiate; else auth_sign
        String putUrl = null;
        JSONObject signObj = upload.optJSONObject("sign");
        if (signObj != null) {
            JSONArray signParts = signObj.optJSONArray("parts");
            if (signParts != null && signParts.length() > 0) {
                putUrl = signParts.getJSONObject(0).optString("url");
            }
            if (putUrl == null || putUrl.length() == 0) {
                putUrl = signObj.optString("url");
            }
        }
        if (putUrl == null || putUrl.length() == 0) {
            JSONObject signBody = new JSONObject();
            signBody.put("uploadID", uploadID);
            JSONArray partNums = new JSONArray();
            partNums.put(1);
            signBody.put("partNumbers", partNums);
            Raw sign = post(KgramServerConfig.IM_API + "/object/auth_sign", signBody, imToken);
            if (sign.errCode != 0 || sign.data == null) {
                return new ApiResult<>(sign.errCode, sign.errMsg, sign.errDlt, null, sign.raw);
            }
            JSONArray signParts = sign.data.optJSONArray("parts");
            if (signParts != null && signParts.length() > 0) {
                putUrl = signParts.getJSONObject(0).optString("url");
            }
            if (putUrl == null || putUrl.length() == 0) {
                putUrl = sign.data.optString("url");
            }
        }
        if (putUrl == null || putUrl.length() == 0) {
            return new ApiResult<>(-1, "no put url", "", null, init.raw);
        }
        HttpURLConnection put = null;
        try {
            put = (HttpURLConnection) new URL(putUrl).openConnection();
            put.setConnectTimeout(20000);
            put.setReadTimeout(60000);
            put.setRequestMethod("PUT");
            put.setDoOutput(true);
            put.setRequestProperty("Content-Type", contentType);
            put.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = put.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            int code = put.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream es = put.getErrorStream();
                String err = es != null ? readAll(es) : "";
                return new ApiResult<>(code, "PUT part failed", err, null, err);
            }
        } finally {
            if (put != null) put.disconnect();
        }
        JSONObject complete = new JSONObject();
        complete.put("uploadID", uploadID);
        JSONArray partMd5s = new JSONArray();
        partMd5s.put(partMd5);
        complete.put("parts", partMd5s);
        complete.put("name", name);
        complete.put("contentType", contentType);
        complete.put("cause", "avatar");
        Raw done = post(KgramServerConfig.IM_API + "/object/complete_multipart_upload", complete, imToken);
        if (done.errCode != 0 || done.data == null) {
            return new ApiResult<>(done.errCode, done.errMsg, done.errDlt, null, done.raw);
        }
        return new ApiResult<>(0, "", "", done.data.optString("url"), done.raw);
    }

    private static void writeFormField(OutputStream os, String boundary, String name, String value) throws Exception {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value != null ? value : "") + "\r\n";
        os.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFormFile(OutputStream os, String boundary, String field, String filename,
                                      String contentType, File file) throws Exception {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.UTF_8));
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        fis.close();
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readFileBytes(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[(int) file.length()];
        int off = 0;
        while (off < buf.length) {
            int n = fis.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        fis.close();
        return buf;
    }

    private static synchronized String getAdminToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedAdminToken != null && now - cachedAdminAt < 50 * 60 * 1000L) {
            return cachedAdminToken;
        }
        JSONObject body = new JSONObject();
        body.put("secret", KgramServerConfig.OPENIM_SECRET);
        body.put("userID", KgramServerConfig.OPENIM_ADMIN_USER);
        Raw raw = post(KgramServerConfig.IM_API + "/auth/get_admin_token", body, null);
        if (raw.errCode != 0 || raw.data == null) {
            throw new IllegalStateException("admin token failed: " + raw.errCode + " " + raw.errMsg);
        }
        cachedAdminToken = raw.data.optString("token");
        cachedAdminAt = now;
        return cachedAdminToken;
    }

    private static String extractText(JSONObject msg) {
        if (msg == null) {
            return "";
        }
        Object content = msg.opt("content");
        String t = extractContentField(content);
        if (t != null && t.length() > 0) {
            return t;
        }
        return msg.optString("content", "");
    }

    private static String extractContentField(Object content) {
        if (content == null || content == JSONObject.NULL) {
            return "";
        }
        if (content instanceof JSONObject) {
            return ((JSONObject) content).optString("content", content.toString());
        }
        String s = String.valueOf(content);
        if (s.startsWith("{")) {
            try {
                return new JSONObject(s).optString("content", s);
            } catch (Exception ignore) {
                return s;
            }
        }
        return s;
    }

    private static final class Raw {
        int errCode;
        String errMsg;
        String errDlt;
        JSONObject data;
        String raw;
    }

    private static Raw post(String urlStr, JSONObject body, String token) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("operationID", "kgram-" + UUID.randomUUID());
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("token", token);
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            Raw raw = new Raw();
            if (is == null) {
                raw.errCode = code;
                raw.errMsg = "HTTP " + code;
                raw.errDlt = "";
                return raw;
            }
            raw.raw = readAll(is);
            JSONObject root = new JSONObject(raw.raw);
            raw.errCode = root.optInt("errCode", -1);
            raw.errMsg = root.optString("errMsg", "");
            raw.errDlt = root.optString("errDlt", "");
            if (root.has("data") && !root.isNull("data")) {
                Object d = root.get("data");
                if (d instanceof JSONObject) {
                    raw.data = (JSONObject) d;
                } else {
                    raw.data = new JSONObject();
                    raw.data.put("value", d);
                }
            }
            return raw;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }
}
