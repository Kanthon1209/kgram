package org.telegram.messenger.kgram;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal OpenIM Chat HTTP client for account/register and account/login.
 */
public final class OpenIMChatClient {
    private OpenIMChatClient() {}

    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    public interface Callback {
        void onDone(Result result);
    }

    public static final class Result {
        public final int errCode;
        public final String errMsg;
        public final String errDlt;
        public final String userID;
        public final String imToken;
        public final String chatToken;
        public final String raw;

        public Result(int errCode, String errMsg, String errDlt, String userID, String imToken, String chatToken, String raw) {
            this.errCode = errCode;
            this.errMsg = errMsg;
            this.errDlt = errDlt;
            this.userID = userID;
            this.imToken = imToken;
            this.chatToken = chatToken;
            this.raw = raw;
        }

        public boolean ok() {
            return errCode == 0;
        }
    }

    public static void registerAsync(String areaCode, String phoneNumber, String password, String nickname, Callback cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(register(areaCode, phoneNumber, password, nickname));
            } catch (Exception e) {
                cb.onDone(new Result(-1, e.getMessage() != null ? e.getMessage() : "network error", "", null, null, null, null));
            }
        });
    }

    public static void loginAsync(String areaCode, String phoneNumber, String password, Callback cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(login(areaCode, phoneNumber, password));
            } catch (Exception e) {
                cb.onDone(new Result(-1, e.getMessage() != null ? e.getMessage() : "network error", "", null, null, null, null));
            }
        });
    }

    public static Result register(String areaCode, String phoneNumber, String password, String nickname) throws Exception {
        JSONObject user = new JSONObject();
        user.put("nickname", nickname != null && nickname.length() > 0 ? nickname : ("kgram_" + phoneNumber));
        user.put("areaCode", areaCode);
        user.put("phoneNumber", phoneNumber);
        user.put("password", password);

        JSONObject body = new JSONObject();
        body.put("verifyCode", KgramServerConfig.SUPER_VERIFY_CODE);
        body.put("platform", KgramServerConfig.PLATFORM_ANDROID);
        body.put("autoLogin", true);
        body.put("user", user);

        return postJson(KgramServerConfig.CHAT_API + "/account/register", body);
    }

    public static Result login(String areaCode, String phoneNumber, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("platform", KgramServerConfig.PLATFORM_ANDROID);
        body.put("areaCode", areaCode);
        body.put("phoneNumber", phoneNumber);
        body.put("password", password);
        return postJson(KgramServerConfig.CHAT_API + "/account/login", body);
    }

    private static Result postJson(String urlStr, JSONObject body) throws Exception {
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

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                return new Result(code, "HTTP " + code, "", null, null, null, null);
            }
            String raw = readAll(is);
            JSONObject root = new JSONObject(raw);
            int errCode = root.optInt("errCode", -1);
            String errMsg = root.optString("errMsg", "");
            String errDlt = root.optString("errDlt", "");
            String userID = null;
            String imToken = null;
            String chatToken = null;
            if (root.has("data") && !root.isNull("data")) {
                JSONObject data = root.getJSONObject("data");
                userID = data.optString("userID", null);
                imToken = data.optString("imToken", null);
                chatToken = data.optString("chatToken", null);
            }
            return new Result(errCode, errMsg, errDlt, userID, imToken, chatToken, raw);
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
