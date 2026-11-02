package org.telegram.messenger.kgram;

/**
 * kgram demo endpoints — OpenIM Chat on Aliyun ECS.
 * Registration/login must hit CHAT_API, not Telegram DCs.
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

    /** Android platform id in OpenIM Chat (1 = iOS, 2 = Android in some docs; server accepted 1 in curl). */
    public static final int PLATFORM_ANDROID = 2;

    public static final String PREFS = "kgram_openim";
}
