package org.telegram.messenger.kgram;

import android.content.Context;
import android.util.Log;
import android.text.TextUtils;

import org.json.JSONObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges OpenIM network data into Telegram-Android UI controllers.
 * UI stays DialogsActivity / ChatActivity / MainTabs / ContactsActivity; OpenIM is network only.
 */
public final class KgramTgBridge {
    private KgramTgBridge() {}

    private static final AtomicInteger localMsgId = new AtomicInteger(-900000);

    /** dialogId -> last applied peer hasReadSeq (outbox). */
    private static final ConcurrentHashMap<Long, Integer> peerHasReadApplied = new ConcurrentHashMap<>();
    /** dialogId -> last seen max message seq (for realtime dedupe). */
    private static final ConcurrentHashMap<Long, Long> knownMaxSeq = new ConcurrentHashMap<>();
    /** Global dedupe keys: c:<clientMsgID>, s:<serverMsgID>, q:<dialogId>:<seq>. */
    private static final ConcurrentHashMap<String, Boolean> seenMsgKeys = new ConcurrentHashMap<>();
    /** Pending optimistic localId -> clientMsgID. */
    private static final ConcurrentHashMap<Integer, String> pendingLocalClientMsg = new ConcurrentHashMap<>();
    /** clientMsgID -> pending optimistic localId. */
    private static final ConcurrentHashMap<String, Integer> pendingClientLocalId = new ConcurrentHashMap<>();
    /** dialogIds currently open in ChatActivity (inbox auto-read). */
    private static final ConcurrentHashMap<Long, Boolean> watchedDialogs = new ConcurrentHashMap<>();
    private static boolean wsStarted = false;
    private static volatile boolean fgListenerRegistered = false;
    private static final String NOTIFY_TAG = "kgram-notify";
    private static final OpenIMWebSocket.Listener wsListener = new OpenIMWebSocket.Listener() {
        @Override
        public void onPushMessages(OpenIMProtoLite.DecodedPush push) {
            handleWsPush(push);
        }

        @Override
        public void onConnected() {
            FileLog.d("kgram bridge ws connected");
            try {
                // Refresh title overlays that listen for connection state.
                NotificationCenter.getInstance(UserConfig.selectedAccount)
                        .postNotificationName(NotificationCenter.didUpdateConnectionState);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }

        @Override
        public void onDisconnected(String reason) {
            FileLog.d("kgram bridge ws disconnected " + reason);
        }
    };

    public static boolean enabled(Context ctx) {
        return KgramServerConfig.USE_OPENIM_AUTH && ctx != null && KgramSession.hasSession(ctx);
    }

    public static boolean enabled() {
        return KgramServerConfig.USE_OPENIM_AUTH
                && UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()
                && KgramSession.hasSession(org.telegram.messenger.ApplicationLoader.applicationContext);
    }

    /** Create/activate a synthetic TG user from OpenIM session so LaunchActivity shows MainTabs. */
    public static void activateFromSession(Context ctx) {
        if (!KgramServerConfig.USE_OPENIM_AUTH || ctx == null || !KgramSession.hasSession(ctx)) {
            return;
        }
        try {
            org.telegram.tgnet.ConnectionsManager.getInstance(UserConfig.selectedAccount).forceOpenImUiConnected();
        } catch (Throwable ignore) {
        }
        int account = UserConfig.selectedAccount;
        UserConfig uc = UserConfig.getInstance(account);
        long myId = parseUserId(KgramSession.userID(ctx));
        if (myId == 0) {
            FileLog.e("kgram bridge: invalid self userID " + KgramSession.userID(ctx));
            return;
        }
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = myId;
        String nick = KgramSession.nickname(ctx);
        if (TextUtils.isEmpty(nick)) {
            nick = "kgram";
        }
        user.first_name = nick;
        user.last_name = "";
        user.phone = KgramSession.phone(ctx);
        user.username = "kgram_" + myId;
        user.access_hash = 1;
        user.status = new TLRPC.TL_userStatusEmpty();
        user.self = true;
        user.contact = true;
        user.mutual_contact = true;
        user.flags |= 1;
        uc.setCurrentUser(user);
        uc.saveConfig(true);
        MessagesController.getInstance(account).putUser(user, false);
        FileLog.d("kgram bridge activated userId=" + myId);
        // Warm dialogs + contacts immediately after login.
        loadDialogsOpenIM(account, 0);
        loadContactsOpenIM(account);
        ensureWebSocket();
        ensurePushRuntime();
        AndroidUtilities.runOnUIThread(KgramNotifications::requestPermissionIfNeeded, 800);
    }

    public static void clearActivation() {
        try {
            try {
                KgramPushService.stop(org.telegram.messenger.ApplicationLoader.applicationContext);
            } catch (Throwable ignore) {
            }
            stopWebSocket();
            peerHasReadApplied.clear();
            knownMaxSeq.clear();
            seenMsgKeys.clear();
            pendingLocalClientMsg.clear();
            pendingClientLocalId.clear();
            watchedDialogs.clear();
            int account = UserConfig.selectedAccount;
            UserConfig.getInstance(account).clearConfig();
            MessagesController.getInstance(account).cleanup();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static long parseUserId(String openImUserId) {
        if (openImUserId == null) {
            return 0;
        }
        try {
            return Long.parseLong(openImUserId.trim());
        } catch (Exception e) {
            long h = openImUserId.hashCode() & 0x7fffffffL;
            return h == 0 ? 1 : h;
        }
    }

    public static String openImId(long tgUserId) {
        return Long.toString(tgUserId);
    }

    private static TLRPC.TL_user toUser(String userID, String nickname, String phoneHint) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = parseUserId(userID);
        String title = nickname;
        if (TextUtils.isEmpty(title)) {
            title = userID;
        }
        user.first_name = title;
        user.last_name = "";
        user.username = "kgram_" + userID;
        if (!TextUtils.isEmpty(phoneHint)) {
            user.phone = phoneHint;
        }
        user.access_hash = 1;
        user.status = new TLRPC.TL_userStatusEmpty();
        user.contact = true;
        user.mutual_contact = true;
        return user;
    }

    /** Called from MessagesController.loadDialogs when OpenIM mode is on. */
    public static boolean loadDialogsOpenIM(final int account, final int folderId) {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx) || folderId != 0) {
            return false;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final String me = KgramSession.userID(ctx);
        final String token = KgramSession.imToken(ctx);
        OpenIMImClient.getConversationsAsync(me, token, convRes -> {
            OpenIMImClient.getFriendsAsync(me, token, friendRes -> {
                OpenIMImClient.getAllUsersAsync(allRes -> AndroidUtilities.runOnUIThread(() -> {
                    try {
                        applyDialogs(account, me, convRes, friendRes, allRes);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    } finally {
                        mc.kgramMarkDialogsLoaded(folderId);
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
                        // Keep contacts in sync with the same snapshot.
                        try {
                            applyContacts(account, me, friendRes, allRes);
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    }
                }));
            });
        });
        return true;
    }

    /** Called from ContactsController.loadContacts when OpenIM mode is on. */
    public static boolean loadContactsOpenIM(final int account) {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx)) {
            return false;
        }
        final String me = KgramSession.userID(ctx);
        final String token = KgramSession.imToken(ctx);
        OpenIMImClient.getFriendsAsync(me, token, friendRes -> {
            OpenIMImClient.getAllUsersAsync(allRes -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    applyContacts(account, me, friendRes, allRes);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }));
        });
        return true;
    }

    private static void applyContacts(int account, String me,
                                      OpenIMImClient.ApiResult<List<OpenIMImClient.Friend>> friendRes,
                                      OpenIMImClient.ApiResult<List<OpenIMImClient.UserInfo>> allRes) {
        Map<String, OpenIMImClient.UserInfo> byId = new HashMap<>();
        if (allRes != null && allRes.ok() && allRes.data != null) {
            for (OpenIMImClient.UserInfo u : allRes.data) {
                if (u != null && u.userID != null && u.userID.length() > 0 && !u.userID.equals(me)) {
                    byId.put(u.userID, u);
                }
            }
        }
        if (friendRes != null && friendRes.ok() && friendRes.data != null) {
            for (OpenIMImClient.Friend f : friendRes.data) {
                if (f == null || f.userID == null || f.userID.equals(me)) continue;
                OpenIMImClient.UserInfo u = byId.get(f.userID);
                if (u == null) {
                    u = new OpenIMImClient.UserInfo();
                    u.userID = f.userID;
                    byId.put(f.userID, u);
                }
                if (!TextUtils.isEmpty(f.nickname)) {
                    u.nickname = f.nickname;
                }
                if (!TextUtils.isEmpty(f.faceURL)) {
                    u.faceURL = f.faceURL;
                }
                if (!TextUtils.isEmpty(f.remark)) {
                    u.nickname = f.remark;
                }
            }
        }

        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>();
        for (OpenIMImClient.UserInfo u : byId.values()) {
            TLRPC.TL_user user = toUser(u.userID, u.nickname, null);
            if (user.id == 0) continue;
            users.add(user);
            TLRPC.TL_contact c = new TLRPC.TL_contact();
            c.user_id = user.id;
            contacts.add(c);
        }
        ContactsController cc = ContactsController.getInstance(account);
        // from=0 mimics server load and rebuilds sections + posts contactsDidLoad
        cc.processLoadedContacts(contacts, users, 0);
        FileLog.d("kgram bridge contacts=" + contacts.size()
                + " friendsOk=" + (friendRes != null && friendRes.ok())
                + " allOk=" + (allRes != null && allRes.ok()));
    }

    private static void applyDialogs(int account, String me,
                                     OpenIMImClient.ApiResult<List<OpenIMImClient.Conversation>> convRes,
                                     OpenIMImClient.ApiResult<List<OpenIMImClient.Friend>> friendRes,
                                     OpenIMImClient.ApiResult<List<OpenIMImClient.UserInfo>> allRes) {
        MessagesController mc = MessagesController.getInstance(account);
        Map<String, OpenIMImClient.UserInfo> names = new HashMap<>();
        if (allRes != null && allRes.ok() && allRes.data != null) {
            for (OpenIMImClient.UserInfo u : allRes.data) {
                if (u != null && u.userID != null) {
                    names.put(u.userID, u);
                }
            }
        }
        if (friendRes != null && friendRes.ok() && friendRes.data != null) {
            for (OpenIMImClient.Friend f : friendRes.data) {
                if (f == null || f.userID == null) continue;
                OpenIMImClient.UserInfo u = names.get(f.userID);
                if (u == null) {
                    u = new OpenIMImClient.UserInfo();
                    u.userID = f.userID;
                    names.put(f.userID, u);
                }
                if (!TextUtils.isEmpty(f.nickname)) {
                    u.nickname = f.nickname;
                }
                if (!TextUtils.isEmpty(f.faceURL)) {
                    u.faceURL = f.faceURL;
                }
                if (!TextUtils.isEmpty(f.remark)) {
                    u.nickname = f.remark;
                }
            }
        }

        List<OpenIMImClient.Conversation> convs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (convRes != null && convRes.ok() && convRes.data != null) {
            for (OpenIMImClient.Conversation c : convRes.data) {
                if (c == null) continue;
                if ((c.userID == null || c.userID.length() == 0) && c.conversationID != null && c.conversationID.startsWith("si_")) {
                    String rest = c.conversationID.substring(3);
                    int idx = rest.indexOf('_');
                    if (idx > 0) {
                        String a = rest.substring(0, idx);
                        String b = rest.substring(idx + 1);
                        c.userID = a.equals(me) ? b : a;
                    }
                }
                if (c.userID == null || c.userID.length() == 0 || c.userID.equals(me)) {
                    continue;
                }
                if (seen.add(c.userID)) {
                    convs.add(c);
                }
            }
        }
        // Friends without an existing conversation still appear as empty chats.
        for (OpenIMImClient.UserInfo u : names.values()) {
            if (u.userID == null || u.userID.equals(me) || !seen.add(u.userID)) {
                continue;
            }
            // Only auto-add actual friends as empty dialogs; all-users stay in contacts.
            boolean isFriend = false;
            if (friendRes != null && friendRes.ok() && friendRes.data != null) {
                for (OpenIMImClient.Friend f : friendRes.data) {
                    if (f != null && u.userID.equals(f.userID)) {
                        isFriend = true;
                        break;
                    }
                }
            }
            if (!isFriend) {
                seen.remove(u.userID);
                continue;
            }
            OpenIMImClient.Conversation c = new OpenIMImClient.Conversation();
            c.userID = u.userID;
            c.showName = u.nickname;
            c.latestMsgContent = "";
            c.latestMsgSendTime = 0;
            convs.add(c);
        }

        FileLog.d("kgram bridge raw convOk=" + (convRes != null && convRes.ok())
                + " convErr=" + (convRes == null ? "null" : convRes.errCode + "/" + convRes.errMsg)
                + " friendOk=" + (friendRes != null && friendRes.ok())
                + " friendErr=" + (friendRes == null ? "null" : friendRes.errCode + "/" + friendRes.errMsg)
                + " convCount=" + convs.size());

        ArrayList<TLRPC.Dialog> keep = new ArrayList<>();
        for (OpenIMImClient.Conversation c : convs) {
            long peerId = parseUserId(c.userID);
            if (peerId == 0) continue;

            String title = c.showName;
            if (TextUtils.isEmpty(title) && names.containsKey(c.userID)) {
                title = names.get(c.userID).nickname;
            }
            if (TextUtils.isEmpty(title)) {
                title = c.userID;
            }

            TLRPC.TL_user user = toUser(c.userID, title, null);
            mc.putUser(user, false);

            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = peerId;
            dialog.peer = new TLRPC.TL_peerUser();
            dialog.peer.user_id = peerId;
            dialog.folder_id = 0;
            int topId = c.latestMsgSeq > 0 ? (int) Math.min(c.latestMsgSeq, Integer.MAX_VALUE) : Math.max(1, c.unreadCount + 1);
            dialog.top_message = topId;
            // Unread badge must come from OpenIM conversation.unreadCount.
            dialog.unread_count = Math.max(0, c.unreadCount);
            // Inbox from unreadCount; outbox ONLY from real peer hasRead receipts.
            dialog.read_inbox_max_id = Math.max(0, topId - dialog.unread_count);
            Integer knownOut = peerHasReadApplied.get(peerId);
            dialog.read_outbox_max_id = knownOut != null ? knownOut : 0;
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            // Ensure muted flag does not hide badge for demo.
            dialog.notify_settings.mute_until = 0;

            long sendTimeMs = c.latestMsgSendTime;
            if (sendTimeMs <= 0) {
                sendTimeMs = System.currentTimeMillis();
            }
            dialog.last_message_date = (int) (sendTimeMs / 1000L);

            String preview = c.latestMsgContent != null ? c.latestMsgContent : "";
            boolean topOut = me != null && me.equals(c.latestMsgSendID);
            // Double-check only when peer hasReadSeq (applied) covers this top message.
            boolean topOutUnread = topOut && topId > dialog.read_outbox_max_id;
            TLRPC.TL_message top = buildMessage(me, c.userID, preview, sendTimeMs, topId, topOut, false, topOutUnread);
            MessageObject mo = new MessageObject(account, top, false, false);
            ArrayList<MessageObject> arr = new ArrayList<>();
            arr.add(mo);
            mc.dialogMessage.put(peerId, arr);
            mc.dialogs_dict.put(peerId, dialog);
            mc.dialogs_read_inbox_max.put(peerId, dialog.read_inbox_max_id);
            mc.dialogs_read_outbox_max.put(peerId, dialog.read_outbox_max_id);
            keep.add(dialog);
        }

        mc.kgramReplaceAllDialogs(keep);
        int unreadSum = 0;
        for (TLRPC.Dialog d0 : keep) {
            if (d0 != null) unreadSum += Math.max(0, d0.unread_count);
        }
        FileLog.d("kgram bridge dialogs=" + keep.size() + " folder0=" + mc.getDialogs(0).size() + " unreadSum=" + unreadSum);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
        ensureWebSocket();
    }

    /** Force-refresh OpenIM conversation list (pull-to-refresh / onResume). */
    public static void forceReloadDialogsOpenIM(final int account) {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx)) {
            return;
        }
        // Bypass MessagesController.loadDialogs end/loading gates; hit OpenIM directly.
        MessagesController.getInstance(account).kgramMarkDialogsLoaded(0);
        loadDialogsOpenIM(account, 0);
    }

    /** Called from loadMessagesInternal — returns true if handled. */
    public static boolean loadMessagesOpenIM(final int account, final long dialogId, final int count,
                                             final int max_id, final int classGuid, final int load_type,
                                             final int last_message_id, final int mode, final int loadIndex) {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx) || dialogId <= 0 || mode != 0) {
            return false;
        }
        final String me = KgramSession.userID(ctx);
        final String peer = openImId(dialogId);
        final String token = KgramSession.imToken(ctx);
        final String convID = OpenIMImClient.singleConversationID(me, peer);
        // Watching is owned by ChatActivity onResume/onPause only.
        ensureWebSocket();
        // Ensure friendship so history/send is allowed on OpenIM.
        OpenIMImClient.ensureFriendAsync(me, peer, ignored -> {
            OpenIMImClient.ensureFriendAsync(peer, me, ignored2 -> {
                OpenIMImClient.searchHistoryAsync(me, peer, token, result -> {
                    // Also fetch peer hasReadSeq + own maxSeq in parallel-ish sequence.
                    java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                    ids.add(convID);
                    OpenIMImClient.getHasReadAndMaxSeqAsync(me, ids, token, selfSeqRes -> {
                        OpenIMImClient.getPeerHasReadSeqAsync(peer, convID, token, peerRes -> AndroidUtilities.runOnUIThread(() -> {
                    MessagesController mc = MessagesController.getInstance(account);
                    TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
                    TLRPC.User peerUser = mc.getUser(dialogId);
                    if (peerUser != null) {
                        res.users.add(peerUser);
                    } else {
                        TLRPC.TL_user created = toUser(peer, peer, null);
                        mc.putUser(created, false);
                        res.users.add(created);
                    }
                    TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
                    if (self != null) {
                        res.users.add(self);
                    }
                    ArrayList<TLRPC.Message> built = new ArrayList<>();
                    HashSet<Long> seenSeq = new HashSet<>();
                    HashSet<String> seenClient = new HashSet<>();
                    long maxSeq = 0;
                    int peerHasRead = 0;
                    Integer appliedOut = peerHasReadApplied.get(dialogId);
                    if (appliedOut != null) {
                        peerHasRead = Math.max(peerHasRead, appliedOut);
                    }
                    if (peerRes != null && peerRes.ok() && peerRes.data != null) {
                        int fromApi = (int) Math.min(Math.max(peerRes.data, 0), Integer.MAX_VALUE);
                        // Real peer read cursor from OpenIM; only advances outbox (never inbox).
                        if (fromApi > peerHasRead) {
                            peerHasRead = fromApi;
                            peerHasReadApplied.put(dialogId, peerHasRead);
                        }
                    }
                    if (result.ok() && result.data != null) {
                        // Ascending by seq for stable TLRPC ids (older = smaller id).
                        ArrayList<OpenIMImClient.ChatMessage> sorted = new ArrayList<>(result.data);
                        sorted.sort((a, b) -> {
                            long sa = a.seq > 0 ? a.seq : a.sendTime;
                            long sb = b.seq > 0 ? b.seq : b.sendTime;
                            int c = Long.compare(sa, sb);
                            if (c != 0) return c;
                            return Long.compare(a.sendTime, b.sendTime);
                        });
                        int fallback = 1;
                        for (OpenIMImClient.ChatMessage m : sorted) {
                            long seq = m.seq > 0 ? m.seq : fallback;
                            if (seq > 0 && !seenSeq.add(seq)) {
                                continue; // dedupe by seq
                            }
                            if (m.clientMsgID != null && m.clientMsgID.length() > 0 && !seenClient.add(m.clientMsgID)) {
                                continue;
                            }
                            if (m.serverMsgID != null && m.serverMsgID.length() > 0 && !seenClient.add("s:" + m.serverMsgID)) {
                                continue;
                            }
                            int id = (int) Math.min(Math.max(seq, 1), Integer.MAX_VALUE);
                            boolean out = me.equals(m.sendID);
                            boolean outUnread = out && id > peerHasRead;
                            TLRPC.TL_message msg = buildMessage(me, peer, m.text, m.sendTime, id, out, false, outUnread);
                            try {
                                long from = parseUserId(m.sendID);
                                msg.from_id = new TLRPC.TL_peerUser();
                                msg.from_id.user_id = from;
                                msg.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
                                msg.out = out;
                            } catch (Throwable ignore) {
                            }
                            built.add(msg);
                            noteMessageKeys(dialogId, seq, m.clientMsgID, m.serverMsgID);
                            if (seq > maxSeq) maxSeq = seq;
                            fallback++;
                        }
                    }
                    if (selfSeqRes != null && selfSeqRes.ok() && selfSeqRes.data != null) {
                        OpenIMImClient.SeqInfo si = selfSeqRes.data.get(convID);
                        if (si != null && si.maxSeq > maxSeq) {
                            maxSeq = si.maxSeq;
                        }
                    }
                    // Telegram history is newest-first.
                    for (int i = built.size() - 1; i >= 0; i--) {
                        res.messages.add(built.get(i));
                    }
                    // Outbox = peer hasReadSeq; inbox = we treat open chat as read up to maxSeq.
                    int outboxMax = peerHasRead;
                    int inboxMax = (int) Math.min(Math.max(maxSeq, 0), Integer.MAX_VALUE);
                    mc.dialogs_read_outbox_max.put(dialogId, outboxMax);
                    mc.dialogs_read_inbox_max.put(dialogId, inboxMax);
                    peerHasReadApplied.put(dialogId, outboxMax);
                    if (maxSeq > 0) {
                        knownMaxSeq.put(dialogId, maxSeq);
                    }
                    TLRPC.Dialog d = mc.dialogs_dict.get(dialogId);
                    if (d != null) {
                        d.read_outbox_max_id = outboxMax;
                        d.read_inbox_max_id = inboxMax;
                        d.unread_count = 0;
                        if (maxSeq > 0) {
                            d.top_message = (int) Math.min(maxSeq, Integer.MAX_VALUE);
                        }
                    }
                    // isCache=false so unread is derived from outbox/inbox max maps above.
                    mc.processLoadedMessages(res, res.messages.size(), dialogId, 0, count, max_id, 0, false, classGuid,
                            0, last_message_id, 0, 0, load_type, true, mode, 0, loadIndex, false, 0, true, false, null);
                    // Only report inbox read while this chat is actually visible.
                    if (maxSeq > 0 && Boolean.TRUE.equals(watchedDialogs.get(dialogId))) {
                        markConversationReadOpenIM(me, peer, maxSeq, token);
                    }
                        }));
                    });
                });
            });
        });
        return true;
    }

    /** Intercept text send for OpenIM peers. */
    public static boolean sendTextOpenIM(final int account, final SendMessagesHelper.SendMessageParams params) {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx) || params == null || params.peer <= 0) {
            return false;
        }
        if (params.message == null || params.message.length() == 0) {
            return false;
        }
        if (params.photo != null || params.document != null || params.videoEditedInfo != null
                || params.poll != null || params.game != null || params.location != null
                || params.sendingStory != null || params.richMessage != null) {
            return false;
        }
        final String me = KgramSession.userID(ctx);
        final String peer = openImId(params.peer);
        final String nick = KgramSession.nickname(ctx);
        final String textMsg = params.message;
        final long dialogId = params.peer;
        final int localId = localMsgId.getAndDecrement();
        final String clientMsgID = java.util.UUID.randomUUID().toString();

        pendingLocalClientMsg.put(localId, clientMsgID);
        pendingClientLocalId.put(clientMsgID, localId);

        TLRPC.TL_message local = buildMessage(me, peer, textMsg, System.currentTimeMillis(), localId, true, true, true);
        local.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        MessageObject mo = new MessageObject(account, local, true, false);
        ArrayList<MessageObject> arr = new ArrayList<>();
        arr.add(mo);
        MessagesController.getInstance(account).dialogMessage.put(dialogId, arr);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didReceiveNewMessages, dialogId, arr, false, 0);

        OpenIMImClient.ensureFriendAsync(me, peer, r1 -> {
            OpenIMImClient.ensureFriendAsync(peer, me, r2 -> {
                OpenIMImClient.sendTextAsync(me, peer, nick, textMsg, clientMsgID, result -> AndroidUtilities.runOnUIThread(() -> {
                    MessagesController mc = MessagesController.getInstance(account);
                    if (!result.ok()) {
                        // Keep the same bubble; flip to error in-place (no delete+re-add pop).
                        clearPendingMaps(localId, clientMsgID);
                        FileLog.e("kgram send fail " + result.errCode + " " + result.errMsg + " " + result.errDlt);
                        local.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageSendError, localId);
                        return;
                    }

                    String gotClient = clientMsgID;
                    String serverMsgID = null;
                    long seq = 0;
                    long sendTime = System.currentTimeMillis();
                    try {
                        if (result.data != null) {
                            JSONObject sendRes = result.data.optJSONObject("sendMsg");
                            if (sendRes == null) {
                                sendRes = result.data;
                            }
                            String c = sendRes.optString("clientMsgID", "");
                            if (c.length() == 0) {
                                c = result.data.optString("clientMsgID", "");
                            }
                            if (c.length() > 0) {
                                gotClient = c;
                            }
                            serverMsgID = sendRes.optString("serverMsgID", "");
                            if (serverMsgID.length() == 0) {
                                serverMsgID = result.data.optString("serverMsgID", "");
                            }
                            if (serverMsgID != null && serverMsgID.length() == 0) {
                                serverMsgID = null;
                            }
                            seq = sendRes.optLong("seq", 0);
                            if (seq <= 0) {
                                seq = result.data.optLong("seq", 0);
                            }
                            long st = sendRes.optLong("sendTime", 0);
                            if (st <= 0) {
                                st = result.data.optLong("sendTime", 0);
                            }
                            if (st > 0) {
                                sendTime = st;
                            }
                        }
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }

                    // If WS already injected this identity/seq, do not post another bubble.
                    boolean alreadyPresent = isMessageKnown(dialogId, seq, gotClient, serverMsgID);
                    if (seq > 0) {
                        noteMessageKeys(dialogId, seq, gotClient, serverMsgID);
                        Long prevMax = knownMaxSeq.get(dialogId);
                        knownMaxSeq.put(dialogId, Math.max(seq, prevMax != null ? prevMax : 0));
                    }

                    // Outbox checkmarks only follow real peer hasReadSeq receipts.
                    Integer applied = peerHasReadApplied.get(dialogId);
                    int keepOut = applied != null ? applied : 0;
                    mc.dialogs_read_outbox_max.put(dialogId, keepOut);

                    if (alreadyPresent) {
                        // WS/history already owns the canonical bubble — remove orphan optimistic only.
                        Integer still = pendingClientLocalId.get(gotClient);
                        if (still == null && clientMsgID != null && !clientMsgID.equals(gotClient)) {
                            still = pendingClientLocalId.get(clientMsgID);
                        }
                        if (still != null) {
                            dropPendingLocal(account, still, gotClient);
                        }
                        clearPendingMaps(localId, clientMsgID);
                        clearPendingMaps(localId, gotClient);
                        FileLog.d("kgram send skip re-post alreadyPresent seq=" + seq + " client=" + gotClient);
                        ensureWebSocket();
                        loadDialogsOpenIM(account, 0);
                        return;
                    }

                    if (seq <= 0) {
                        // No server seq yet — keep the same bubble; mark sent; WS will promote by clientMsgID.
                        pendingLocalClientMsg.put(localId, gotClient);
                        pendingClientLocalId.put(gotClient, localId);
                        if (clientMsgID != null && !clientMsgID.equals(gotClient)) {
                            pendingClientLocalId.remove(clientMsgID);
                        }
                        local.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageReceivedByAck, localId);
                        FileLog.d("kgram send waiting for seq client=" + gotClient);
                        ensureWebSocket();
                        loadDialogsOpenIM(account, 0);
                        return;
                    }

                    int serverId = (int) Math.min(seq, Integer.MAX_VALUE);
                    TLRPC.TL_message sent = buildMessage(me, peer, textMsg, sendTime, serverId, true, false, true);
                    sent.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
                    sent.unread = serverId > keepOut;
                    TLRPC.Dialog d = mc.dialogs_dict.get(dialogId);
                    if (d != null) {
                        d.read_outbox_max_id = keepOut;
                        d.last_message_date = sent.date;
                    }
                    // In-place localId → serverId (no messagesDeleted + didReceiveNewMessages double pop).
                    promotePendingToServer(account, dialogId, localId, gotClient, sent, serverId);
                    if (clientMsgID != null && !clientMsgID.equals(gotClient)) {
                        clearPendingMaps(localId, clientMsgID);
                    }
                    ensureWebSocket();
                    // Refresh conversation list preview only; do not re-inject full history here.
                    loadDialogsOpenIM(account, 0);
                }));
            });
        });
        return true;
    }

    /**
     * @param sending if true, mark as sending
     * @param outUnread for outgoing: true = peer has not read (single check); false = double check
     */
    private static TLRPC.TL_message buildMessage(String me, String peerOpenIm, String text, long sendTimeMs, int id, boolean out, boolean sending, boolean outUnread) {
        long peerId = parseUserId(peerOpenIm);
        long myId = parseUserId(me);
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = id == 0 ? 1 : id;
        msg.message = text != null ? text : "";
        int date = sendTimeMs > 0 ? (int) (sendTimeMs / 1000L) : (int) (System.currentTimeMillis() / 1000L);
        msg.date = date;
        msg.dialog_id = peerId;
        // Outgoing: unread=true means peer has not read (single check). Incoming in open chat: read.
        msg.unread = out && outUnread;
        msg.media_unread = false;
        msg.out = out;
        msg.send_state = sending ? MessageObject.MESSAGE_SEND_STATE_SENDING : MessageObject.MESSAGE_SEND_STATE_SENT;
        msg.peer_id = new TLRPC.TL_peerUser();
        msg.peer_id.user_id = peerId;
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = out ? myId : peerId;
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        msg.attachPath = "";
        return msg;
    }

    /** Mark our own conversation cursor as read on OpenIM (fires receipt to peer). */
    public static void markConversationReadOpenIM(String me, String peer, long hasReadSeq, String token) {
        if (me == null || peer == null || hasReadSeq <= 0) {
            return;
        }
        String convID = OpenIMImClient.singleConversationID(me, peer);
        OpenIMImClient.markConversationAsReadAsync(me, convID, hasReadSeq, token, res -> {
            if (res == null || !res.ok()) {
                FileLog.e("kgram mark read fail " + (res == null ? "null" : res.errCode + "/" + res.errMsg + "/" + res.errDlt));
            } else {
                FileLog.d("kgram mark read ok " + convID + " seq=" + hasReadSeq);
            }
        });
    }

    /** Called from MessagesController.markDialogAsRead / completeReadTask. */
    public static boolean markDialogAsReadOpenIM(final int account, final long dialogId, final int maxId) {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx) || dialogId <= 0 || maxId <= 0) {
            return false;
        }
        final String me = KgramSession.userID(ctx);
        final String peer = openImId(dialogId);
        final String token = KgramSession.imToken(ctx);
        MessagesController mc = MessagesController.getInstance(account);
        mc.dialogs_read_inbox_max.put(dialogId, Math.max(maxId, mc.dialogs_read_inbox_max.containsKey(dialogId) ? mc.dialogs_read_inbox_max.get(dialogId) : 0));
        TLRPC.Dialog d = mc.dialogs_dict.get(dialogId);
        if (d != null) {
            d.read_inbox_max_id = Math.max(d.read_inbox_max_id, maxId);
            d.unread_count = 0;
        }
        markConversationReadOpenIM(me, peer, maxId, token);
        // Do NOT set watchedDialogs here — ChatActivity onPause calls markDialogAsReadNow
        // which would re-arm watch=true after Home and suppress system notifications.
        ensureWebSocket();
        return true;
    }

    public static void watchDialogForReadReceipts(long dialogId, boolean watch) {
        if (dialogId <= 0) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        if (watch) {
            // Never mark as open while process is backgrounded (Home race).
            boolean fg = true;
            try {
                ForegroundDetector det = ForegroundDetector.getInstance();
                if (det != null) {
                    fg = det.isForeground();
                }
            } catch (Throwable ignore) {
            }
            if (!fg) {
                Log.e(NOTIFY_TAG, "ignore watch=true while background dialog=" + dialogId);
                watchedDialogs.remove(dialogId);
                return;
            }
            watchedDialogs.put(dialogId, Boolean.TRUE);
            try {
                org.telegram.messenger.NotificationsController.getInstance(account).setOpenedDialogId(dialogId, 0);
            } catch (Throwable ignore) {
            }
            KgramNotifications.cancelDialog(account, dialogId);
        } else {
            watchedDialogs.remove(dialogId);
            try {
                org.telegram.messenger.NotificationsController.getInstance(account).setOpenedDialogId(0, 0);
            } catch (Throwable ignore) {
            }
        }
    }

    /** Clear all open-chat watches — called when app goes to background. */
    public static void clearWatchedDialogsForBackground() {
        if (!watchedDialogs.isEmpty()) {
            Log.e(NOTIFY_TAG, "clear watchedDialogs on background size=" + watchedDialogs.size());
        }
        watchedDialogs.clear();
        try {
            org.telegram.messenger.NotificationsController.getInstance(UserConfig.selectedAccount)
                    .setOpenedDialogId(0, 0);
        } catch (Throwable ignore) {
        }
    }

    private static void registerForegroundListenerOnce() {
        if (fgListenerRegistered) {
            return;
        }
        try {
            ForegroundDetector det = ForegroundDetector.getInstance();
            if (det == null) {
                return;
            }
            fgListenerRegistered = true;
            det.addListener(new ForegroundDetector.Listener() {
                @Override
                public void onBecameForeground() {
                    Log.e(NOTIFY_TAG, "app foreground");
                    AndroidUtilities.runOnUIThread(KgramNotifications::requestPermissionIfNeeded, 400);
                    try {
                        KgramPushService.ensureStarted(org.telegram.messenger.ApplicationLoader.applicationContext);
                    } catch (Throwable ignore) {
                    }
                }

                @Override
                public void onBecameBackground() {
                    Log.e(NOTIFY_TAG, "app background — clear watches + ensure FGS");
                    clearWatchedDialogsForBackground();
                    try {
                        KgramPushService.ensureStarted(org.telegram.messenger.ApplicationLoader.applicationContext);
                    } catch (Throwable ignore) {
                    }
                    ensureWebSocket();
                }
            });
            Log.e(NOTIFY_TAG, "ForegroundDetector listener registered");
        } catch (Throwable t) {
            Log.e(NOTIFY_TAG, "registerForegroundListener failed", t);
            FileLog.e(t);
        }
    }

    private static void ensureWebSocket() {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx)) {
            return;
        }
        try {
            KgramNotifications.ensureChannel(ctx);
        } catch (Throwable ignore) {
        }
        String me = KgramSession.userID(ctx);
        String token = KgramSession.imToken(ctx);
        OpenIMWebSocket ws = OpenIMWebSocket.getInstance();
        ws.setListener(wsListener);
        if (!wsStarted || !ws.isRunning() || !ws.isConnected()) {
            wsStarted = true;
            ws.start(me, token);
            android.util.Log.e(NOTIFY_TAG, "ensureWebSocket start/reconnect running="
                    + ws.isRunning() + " connected=" + ws.isConnected());
        }
        try {
            KgramPushService.ensureStarted(ctx);
        } catch (Throwable ignore) {
        }
    }

    /** Called from KgramPushService / activate to (re)bind WS + channels. */
    public static void ensurePushRuntime() {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!KgramServerConfig.USE_OPENIM_AUTH || ctx == null || !KgramSession.hasSession(ctx)) {
            return;
        }
        registerForegroundListenerOnce();
        ensureWebSocket();
        AndroidUtilities.runOnUIThread(KgramNotifications::requestPermissionIfNeeded, 300);
        try {
            KgramNotifications.ensureChannel(ctx);
            KgramNotifications.dumpState(ctx);
        } catch (Throwable ignore) {
        }
    }

    private static void stopWebSocket() {
        wsStarted = false;
        try {
            OpenIMWebSocket.getInstance().stop();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Handle OpenIM WS push (2001): new messages + hasReadSeq receipts. */
    private static void handleWsPush(OpenIMProtoLite.DecodedPush push) {
        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!enabled(ctx) || push == null) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        final String me = KgramSession.userID(ctx);
        try {
            if (push.receipts != null) {
                for (OpenIMProtoLite.ReadReceipt rr : push.receipts) {
                    if (rr == null || rr.hasReadSeq <= 0) continue;
                    // Only apply outbox when the *peer* marked read.
                    if (rr.markAsReadUserID != null && rr.markAsReadUserID.equals(me)) {
                        continue;
                    }
                    String peer = OpenIMProtoLite.peerFromConversationID(rr.conversationID, me);
                    if (peer == null || peer.length() == 0) {
                        continue;
                    }
                    long did = parseUserId(peer);
                    int has = (int) Math.min(rr.hasReadSeq, Integer.MAX_VALUE);
                    applyPeerOutboxRead(account, did, has);
                }
            }
            // Collect peers touched by this push (msgs + receipt conversations).
            HashSet<String> peersToSync = new HashSet<>();
            if (push.messages != null && !push.messages.isEmpty()) {
                HashMap<String, ArrayList<OpenIMImClient.ChatMessage>> byPeer = new HashMap<>();
                for (OpenIMProtoLite.PushMsg pm : push.messages) {
                    if (pm == null) continue;
                    String peer = null;
                    if (pm.conversationID != null && pm.conversationID.startsWith("si_")) {
                        peer = OpenIMProtoLite.peerFromConversationID(pm.conversationID, me);
                    }
                    if (peer == null) {
                        if (me.equals(pm.sendID)) {
                            peer = pm.recvID;
                        } else if (me.equals(pm.recvID)) {
                            peer = pm.sendID;
                        }
                    }
                    if (peer == null || peer.length() == 0) {
                        continue;
                    }
                    peersToSync.add(peer);
                    OpenIMImClient.ChatMessage cm = new OpenIMImClient.ChatMessage();
                    cm.sendID = pm.sendID;
                    cm.recvID = pm.recvID;
                    cm.clientMsgID = pm.clientMsgID;
                    cm.serverMsgID = pm.serverMsgID;
                    cm.senderNickname = pm.senderNickname;
                    cm.contentType = pm.contentType;
                    cm.seq = pm.seq;
                    cm.sendTime = pm.bestSendTimeMs();
                    cm.text = OpenIMProtoLite.extractText(pm.content);
                    if (cm.text == null) {
                        cm.text = "";
                    }
                    // Direct inject only when server seq is present; otherwise history sync below.
                    if (cm.seq > 0) {
                        ArrayList<OpenIMImClient.ChatMessage> list = byPeer.get(peer);
                        if (list == null) {
                            list = new ArrayList<>();
                            byPeer.put(peer, list);
                        }
                        list.add(cm);
                    }
                }
                for (Map.Entry<String, ArrayList<OpenIMImClient.ChatMessage>> e : byPeer.entrySet()) {
                    String peer = e.getKey();
                    long did = parseUserId(peer);
                    OpenIMImClient.ApiResult<List<OpenIMImClient.ChatMessage>> fake =
                            new OpenIMImClient.ApiResult<>(0, "", "", e.getValue(), null);
                    injectNewMessagesFromPoll(account, me, peer, did, fake);
                }
            }
            if (push.receipts != null) {
                for (OpenIMProtoLite.ReadReceipt rr : push.receipts) {
                    if (rr == null) continue;
                    String peer = OpenIMProtoLite.peerFromConversationID(rr.conversationID, me);
                    if (peer != null) peersToSync.add(peer);
                }
            }
            // WS-triggered history pull for authoritative seq / ordering (not a timer poll).
            final String token = KgramSession.imToken(ctx);
            for (String peer : peersToSync) {
                if (peer == null || peer.length() == 0) continue;
                final String p = peer;
                final long did = parseUserId(p);
                OpenIMImClient.searchHistoryAsync(me, p, token, result -> AndroidUtilities.runOnUIThread(() -> {
                    try {
                        injectNewMessagesFromPoll(account, me, p, did, result);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }


    /** Inject newly received OpenIM messages (WS push or one-shot sync) into TG UI. */
    private static void injectNewMessagesFromPoll(int account, String me, String peer, long dialogId,
                                                  OpenIMImClient.ApiResult<List<OpenIMImClient.ChatMessage>> result) {
        if (result == null || !result.ok() || result.data == null || result.data.isEmpty()) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        long last = 0;
        Long known = knownMaxSeq.get(dialogId);
        if (known != null) {
            last = known;
        } else {
            TLRPC.Dialog d = mc.dialogs_dict.get(dialogId);
            if (d != null) {
                last = Math.max(0, d.top_message);
            }
        }
        ArrayList<OpenIMImClient.ChatMessage> sorted = new ArrayList<>(result.data);
        sorted.sort((a, b) -> {
            long sa = a.seq > 0 ? a.seq : a.sendTime;
            long sb = b.seq > 0 ? b.seq : b.sendTime;
            return Long.compare(sa, sb);
        });
        long newMax = last;
        ArrayList<MessageObject> fresh = new ArrayList<>();
        Integer outbox = mc.dialogs_read_outbox_max.get(dialogId);
        int peerHas = outbox != null ? outbox : (peerHasReadApplied.get(dialogId) != null ? peerHasReadApplied.get(dialogId) : 0);
        for (OpenIMImClient.ChatMessage m : sorted) {
            long seq = m.seq > 0 ? m.seq : 0;
            String clientId = m.clientMsgID;
            String serverId = m.serverMsgID;

            Integer loc = null;
            if (clientId != null && clientId.length() > 0) {
                loc = pendingClientLocalId.get(clientId);
            }

            boolean already = isMessageKnown(dialogId, seq, clientId, serverId);
            if (already) {
                if (seq > newMax) {
                    newMax = seq;
                }
                // Bind any new seq/client/server aliases together.
                noteMessageKeys(dialogId, seq, clientId, serverId);
                // Canonical already known: promote or drop leftover optimistic (never re-add).
                if (loc != null) {
                    if (seq > 0) {
                        int id = (int) Math.min(seq, Integer.MAX_VALUE);
                        boolean out = me.equals(m.sendID);
                        boolean outUnread = out && id > peerHas;
                        TLRPC.TL_message msg = buildMessage(me, peer, m.text, m.sendTime, id, out, false, outUnread);
                        promotePendingToServer(account, dialogId, loc, clientId, msg, id);
                    } else {
                        dropPendingLocal(account, loc, clientId);
                    }
                }
                continue;
            }

            // Pending optimistic for this clientMsgID → in-place localId→seq upgrade (one bubble).
            if (loc != null && seq > 0) {
                noteMessageKeys(dialogId, seq, clientId, serverId);
                int id = (int) Math.min(seq, Integer.MAX_VALUE);
                boolean out = me.equals(m.sendID);
                boolean outUnread = out && id > peerHas;
                TLRPC.TL_message msg = buildMessage(me, peer, m.text, m.sendTime, id, out, false, outUnread);
                promotePendingToServer(account, dialogId, loc, clientId, msg, id);
                if (seq > newMax) {
                    newMax = seq;
                }
                continue;
            }
            if (loc != null) {
                // No seq yet — leave the optimistic bubble alone.
                continue;
            }

            // Already covered by knownMaxSeq from a prior REST send — do not re-bubble.
            if (seq > 0 && seq <= last) {
                noteMessageKeys(dialogId, seq, clientId, serverId);
                continue;
            }
            if (seq <= 0 && (clientId == null || clientId.length() == 0) && (serverId == null || serverId.length() == 0)) {
                continue;
            }

            noteMessageKeys(dialogId, seq, clientId, serverId);
            int id = seq > 0 ? (int) Math.min(seq, Integer.MAX_VALUE) : localMsgId.getAndDecrement();
            boolean out = me.equals(m.sendID);
            boolean outUnread = out && (seq <= 0 || id > peerHas);
            TLRPC.TL_message msg = buildMessage(me, peer, m.text, m.sendTime, id, out, false, outUnread);
            MessageObject mo = new MessageObject(account, msg, true, false);
            fresh.add(mo);
            if (seq > newMax) {
                newMax = seq;
            }
        }
        if (newMax > last) {
            knownMaxSeq.put(dialogId, newMax);
        }
        if (fresh.isEmpty()) {
            return;
        }
        MessageObject lastMo = fresh.get(fresh.size() - 1);
        ArrayList<MessageObject> preview = new ArrayList<>();
        preview.add(lastMo);
        mc.dialogMessage.put(dialogId, preview);
        TLRPC.Dialog d = mc.dialogs_dict.get(dialogId);
        if (d != null) {
            d.top_message = (int) Math.min(Math.max(newMax, d.top_message), Integer.MAX_VALUE);
            d.last_message_date = lastMo.messageOwner.date;
            // Incoming increments unread unless this chat is actively watched/open.
            boolean open = Boolean.TRUE.equals(watchedDialogs.get(dialogId));
            int incomingNew = 0;
            for (MessageObject mo : fresh) {
                if (mo != null && !mo.isOut()) {
                    incomingNew++;
                }
            }
            if (open) {
                d.unread_count = 0;
                d.read_inbox_max_id = Math.max(d.read_inbox_max_id, (int) Math.min(newMax, Integer.MAX_VALUE));
                mc.dialogs_read_inbox_max.put(dialogId, d.read_inbox_max_id);
                // Mark OpenIM read while chat is open.
                markConversationReadOpenIM(me, peer, newMax, KgramSession.imToken(org.telegram.messenger.ApplicationLoader.applicationContext));
            } else if (incomingNew > 0) {
                d.unread_count = Math.max(0, d.unread_count) + incomingNew;
            }
        } else {
            // Create a minimal dialog so list can show the peer.
            TLRPC.TL_user user = toUser(peer, peer, null);
            mc.putUser(user, false);
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = dialogId;
            dialog.peer = new TLRPC.TL_peerUser();
            dialog.peer.user_id = dialogId;
            dialog.top_message = (int) Math.min(newMax, Integer.MAX_VALUE);
            dialog.last_message_date = lastMo.messageOwner.date;
            dialog.unread_count = lastMo.isOut() ? 0 : fresh.size();
            dialog.read_inbox_max_id = 0;
            dialog.read_outbox_max_id = peerHas;
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            mc.dialogs_dict.put(dialogId, dialog);
            ArrayList<TLRPC.Dialog> all = new ArrayList<>();
            for (int i = 0; i < mc.getDialogs(0).size(); i++) {
                all.add(mc.getDialogs(0).get(i));
            }
            all.add(dialog);
            mc.kgramReplaceAllDialogs(all);
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didReceiveNewMessages, dialogId, fresh, false, 0);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
        // System notification shade for inbound messages when chat is not open / app backgrounded.
        boolean appForeground = true;
        try {
            ForegroundDetector det = ForegroundDetector.getInstance();
            if (det != null) {
                appForeground = det.isForeground();
            }
        } catch (Throwable ignore) {
        }
        boolean chatOpen = Boolean.TRUE.equals(watchedDialogs.get(dialogId));
        // Hard rule: backgrounded process ALWAYS notifies (Home / multi-task race).
        if (!appForeground) {
            chatOpen = false;
        }
        Log.e(NOTIFY_TAG, "inject notify gate dialog=" + dialogId
                + " fresh=" + fresh.size()
                + " chatOpen=" + chatOpen
                + " appFg=" + appForeground
                + " watched=" + watchedDialogs.containsKey(dialogId));
        if (!chatOpen) {
            String peerTitle = KgramNotifications.resolvePeerTitle(account, dialogId, peer);
            ArrayList<MessageObject> inbound = new ArrayList<>();
            for (MessageObject mo : fresh) {
                if (mo == null || mo.isOut() || mo.messageOwner == null) {
                    continue;
                }
                inbound.add(mo);
                String notifPreview = mo.messageOwner.message;
                Log.e(NOTIFY_TAG, "notify attempt dialog=" + dialogId + " mid=" + mo.getId());
                FileLog.e("kgram notify attempt dialog=" + dialogId + " mid=" + mo.getId());
                KgramNotifications.notifyIncoming(account, dialogId, peerTitle, notifPreview, mo.getId());
            }
            // Dual path: also feed TG NotificationsController (uses its own channels).
            if (!inbound.isEmpty()) {
                try {
                    org.telegram.messenger.NotificationsController.getInstance(account)
                            .processNewMessages(inbound, true, false, null);
                } catch (Throwable t) {
                    Log.e(NOTIFY_TAG, "processNewMessages failed", t);
                    FileLog.e(t);
                }
            }
        } else {
            KgramNotifications.cancelDialog(account, dialogId);
        }
        FileLog.d("kgram ws inject dialog=" + dialogId + " new=" + fresh.size() + " maxSeq=" + newMax);
    }


    private static String keyClient(String clientMsgID) {
        return (clientMsgID == null || clientMsgID.length() == 0) ? null : ("c:" + clientMsgID);
    }

    private static String keyServer(String serverMsgID) {
        return (serverMsgID == null || serverMsgID.length() == 0) ? null : ("s:" + serverMsgID);
    }

    private static String keySeq(long dialogId, long seq) {
        return seq > 0 ? ("q:" + dialogId + ":" + seq) : null;
    }

    private static boolean hasAnyIdentity(String clientMsgID, String serverMsgID) {
        return (clientMsgID != null && clientMsgID.length() > 0) || (serverMsgID != null && serverMsgID.length() > 0);
    }

    private static boolean isMessageKnown(long dialogId, long seq, String clientMsgID, String serverMsgID) {
        String kc = keyClient(clientMsgID);
        if (kc != null && seenMsgKeys.containsKey(kc)) {
            return true;
        }
        String ks = keyServer(serverMsgID);
        if (ks != null && seenMsgKeys.containsKey(ks)) {
            return true;
        }
        String kq = keySeq(dialogId, seq);
        if (kq != null && seenMsgKeys.containsKey(kq)) {
            return true;
        }
        return false;
    }

    /**
     * Mark identity keys for a message. Returns true if any key was already known
     * (caller should not post another bubble).
     */
    private static boolean noteMessageKeys(long dialogId, long seq, String clientMsgID, String serverMsgID) {
        boolean already = isMessageKnown(dialogId, seq, clientMsgID, serverMsgID);
        String kc = keyClient(clientMsgID);
        if (kc != null) {
            seenMsgKeys.put(kc, Boolean.TRUE);
        }
        String ks = keyServer(serverMsgID);
        if (ks != null) {
            seenMsgKeys.put(ks, Boolean.TRUE);
        }
        String kq = keySeq(dialogId, seq);
        if (kq != null) {
            seenMsgKeys.put(kq, Boolean.TRUE);
        }
        return already;
    }

    private static void clearPendingMaps(int localId, String clientMsgID) {
        if (clientMsgID != null && clientMsgID.length() > 0) {
            pendingClientLocalId.remove(clientMsgID);
        }
        String mapped = pendingLocalClientMsg.remove(localId);
        if (mapped != null) {
            pendingClientLocalId.remove(mapped);
        }
    }

    private static void dropPendingLocal(int account, int localId, String clientMsgID) {
        clearPendingMaps(localId, clientMsgID);
        ArrayList<Integer> del = new ArrayList<>();
        del.add(localId);
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.messagesDeleted, del, 0L, false, false, false, 0);
    }

    /**
     * Upgrade an optimistic local bubble to the server seq/id in-place via TG's native
     * messageReceivedByServer path (row update, not remove+insert animation).
     */
    private static void promotePendingToServer(int account, long dialogId, int localId, String clientMsgID,
                                               TLRPC.Message sentMsg, int serverId) {
        clearPendingMaps(localId, clientMsgID);
        if (sentMsg == null) {
            return;
        }
        sentMsg.id = serverId;
        sentMsg.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
        MessagesController mc = MessagesController.getInstance(account);
        ArrayList<MessageObject> preview = new ArrayList<>();
        preview.add(new MessageObject(account, sentMsg, true, false));
        mc.dialogMessage.put(dialogId, preview);
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.postNotificationName(NotificationCenter.messageReceivedByServer,
                localId, serverId, sentMsg, dialogId, 0L, 0, false);
        nc.postNotificationName(NotificationCenter.messageReceivedByServer2,
                localId, serverId, sentMsg, dialogId, 0L, 0, false);
        FileLog.d("kgram promote local=" + localId + " -> server=" + serverId + " dialog=" + dialogId
                + " client=" + clientMsgID);
    }

    /** Apply peer hasReadSeq as TG read_outbox_max (double-check for id <= peerHas). */
    public static void applyPeerOutboxRead(int account, long dialogId, int peerHasReadSeq) {
        if (dialogId <= 0 || peerHasReadSeq < 0) {
            return;
        }
        Integer prev = peerHasReadApplied.get(dialogId);
        if (prev != null && peerHasReadSeq <= prev) {
            // Still refresh map in case UI missed
            if (peerHasReadSeq == prev) {
                return;
            }
        }
        MessagesController mc = MessagesController.getInstance(account);
        Integer cur = mc.dialogs_read_outbox_max.get(dialogId);
        int next = peerHasReadSeq;
        if (cur != null) {
            next = Math.max(cur, peerHasReadSeq);
        }
        if (prev != null && next <= prev && cur != null && next <= cur) {
            return;
        }
        peerHasReadApplied.put(dialogId, next);
        mc.dialogs_read_outbox_max.put(dialogId, next);
        TLRPC.Dialog d = mc.dialogs_dict.get(dialogId);
        if (d != null) {
            d.read_outbox_max_id = Math.max(d.read_outbox_max_id, next);
        }
        // Update dialog preview message unread flag
        ArrayList<MessageObject> preview = mc.dialogMessage.get(dialogId);
        if (preview != null) {
            for (MessageObject mo : preview) {
                if (mo != null && mo.messageOwner != null && mo.messageOwner.out && mo.getId() > 0 && mo.getId() <= next) {
                    mo.messageOwner.unread = false;
                    mo.setIsRead();
                }
            }
        }
        LongSparseIntArray outbox = new LongSparseIntArray(1);
        outbox.put(dialogId, next);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesRead, null, outbox);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
        FileLog.d("kgram peer read outbox dialog=" + dialogId + " hasReadSeq=" + next);
    }

}
