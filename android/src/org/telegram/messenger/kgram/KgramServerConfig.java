package org.telegram.messenger.kgram;

/**
 * kgram demo endpoints — OpenIM Chat on Aliyun ECS.
 * Registration/login must hit CHAT_API, not Telegram DCs.
 * Realtime: OpenIM msggateway WebSocket (sdkType=js). Send still admin REST for demo.
 */
public final class KgramServerConfig {
    private KgramServerConfig() {}

    /** When true, LoginActivity PhoneView skips MTProto sendCode and uses OpenIM Chat. */
    public static final boolean USE_OPENIM_AUTH = true;

    public static final String CHAT_API = "http://8.136.150.149:10008";
    public static final String IM_API = "http://8.136.150.149:10002";
    public static final String IM_WS = "ws://8.136.150.149:10001";
    public static final String MINIO = "http://8.136.150.149:10005";

    /** OpenIM Chat demo super verify code (verifyCode.superCode). */
    public static final String SUPER_VERIFY_CODE = "666666";

    /** Android platform id in OpenIM Chat. */
    public static final int PLATFORM_ANDROID = 2;

    public static final String PREFS = "kgram_openim";

    /**
     * Demo-only: OpenIM server secret for admin token (send_msg / import_friend).
     * Not for production — replace with app-server proxy later.
     */
    public static final String OPENIM_SECRET = "openIM123";
    public static final String OPENIM_ADMIN_USER = "imAdmin";

    /**
     * @deprecated Realtime uses OpenIM WebSocket ({@link OpenIMWebSocket}); kept only for
     * legacy KgramChatActivity no-op path.
     */
    @Deprecated
    public static final int CHAT_POLL_MS = 2000;
}
