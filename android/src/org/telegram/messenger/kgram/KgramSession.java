package org.telegram.messenger.kgram;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * OpenIM session stored after Chat register/login.
 */
public final class KgramSession {
    private KgramSession() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(KgramServerConfig.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasSession(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String userID = sp.getString("userID", null);
        String imToken = sp.getString("imToken", null);
        return userID != null && imToken != null && userID.length() > 0 && imToken.length() > 0;
    }

    public static String userID(Context ctx) {
        return prefs(ctx).getString("userID", "");
    }

    public static String imToken(Context ctx) {
        return prefs(ctx).getString("imToken", "");
    }

    public static String nickname(Context ctx) {
        String n = prefs(ctx).getString("nickname", "");
        if (n == null || n.length() == 0) {
            return userID(ctx);
        }
        return n;
    }

    public static String phone(Context ctx) {
        return prefs(ctx).getString("phoneNumber", "");
    }

    public static void save(Context ctx, String areaCode, String phoneNumber, String nickname,
                            String userID, String imToken, String chatToken) {
        prefs(ctx).edit()
                .putString("userID", userID)
                .putString("imToken", imToken)
                .putString("chatToken", chatToken != null ? chatToken : "")
                .putString("areaCode", areaCode != null ? areaCode : "")
                .putString("phoneNumber", phoneNumber != null ? phoneNumber : "")
                .putString("nickname", nickname != null ? nickname : "")
                .putString("imApi", KgramServerConfig.IM_API)
                .putString("imWs", KgramServerConfig.IM_WS)
                .putString("chatApi", KgramServerConfig.CHAT_API)
                .apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
