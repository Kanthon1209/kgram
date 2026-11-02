package org.telegram.messenger.kgram;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal OpenIM Chat HTTP client for account/register and account/login.
 */
public final class OpenIMChatClient {
    private OpenIMChatClient() {}

    public static final int ERR_NETWORK = -1;
    public static final int ERR_ARGS = 1001;
    public static final int ERR_PASSWORD = 20001;
    public static final int ERR_ACCOUNT_NOT_FOUND = 20002;
    public static final int ERR_ALREADY_REGISTERED = 20004;
    public static final int ERR_VERIFY_CODE = 20006;

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
            this.errMsg = errMsg != null ? errMsg : "";
            this.errDlt = errDlt != null ? errDlt : "";
            this.userID = userID;
            this.imToken = imToken;
            this.chatToken = chatToken;
            this.raw = raw;
        }

        public boolean ok() {
            return errCode == 0;
        }

        public boolean alreadyRegistered() {
            return errCode == ERR_ALREADY_REGISTERED;
        }

        public boolean accountNotFound() {
            return errCode == ERR_ACCOUNT_NOT_FOUND;
        }

        public boolean passwordError() {
            return errCode == ERR_PASSWORD;
        }
    }

    /** Ensure area code looks like +86 (leading +). */
    public static String normalizeAreaCode(String areaCode) {
        if (areaCode == null) {
            return "";
        }
        String digits = areaCode.replaceAll("[^0-9]", "");
        if (digits.length() == 0) {
            return "";
        }
        return "+" + digits;
    }

    /** Digits-only phone number (no spaces / dashes). */
    public static String normalizePhone(String phoneNumber) {
        if (phoneNumber == null) {
            return "";
        }
        return phoneNumber.replaceAll("[^0-9]", "");
    }

    /** User-facing Chinese message for OpenIM / network errors. */
    public static String friendlyMessage(Result result) {
        if (result == null) {
            return "未知错误";
        }
        switch (result.errCode) {
            case 0:
                return "成功";
            case ERR_NETWORK:
                return networkHint(result.errMsg);
            case ERR_ARGS:
                return argsHint(result.errDlt, result.errMsg);
            case ERR_PASSWORD:
                return "密码错误，请重试";
            case ERR_ACCOUNT_NOT_FOUND:
                return "该手机号尚未注册，请先点「注册」";
            case ERR_ALREADY_REGISTERED:
                return "该手机号已注册，请改用「登录」";
            case ERR_VERIFY_CODE:
                return "验证码不正确（演示请用 " + KgramServerConfig.SUPER_VERIFY_CODE + "）";
            default: {
                String detail = result.errDlt != null && result.errDlt.length() > 0 ? result.errDlt : result.errMsg;
                if (detail == null || detail.length() == 0) {
                    detail = "errCode=" + result.errCode;
                }
                return "请求失败：" + detail + " (" + result.errCode + ")";
            }
        }
    }

    private static String argsHint(String errDlt, String errMsg) {
        String d = errDlt != null ? errDlt : "";
        String lower = d.toLowerCase();
        if (lower.contains("nickname")) {
            return "请填写昵称";
        }
        if (lower.contains("phone") && lower.contains("empty")) {
            return "请填写手机号";
        }
        if (lower.contains("phone")) {
            return "手机号格式不正确";
        }
        if (lower.contains("password")) {
            return "密码不符合要求";
        }
        if (d.length() > 0) {
            return "参数错误：" + d;
        }
        if (errMsg != null && errMsg.length() > 0) {
            return "参数错误：" + errMsg;
        }
        return "参数错误，请检查手机号 / 区号 / 昵称 / 密码";
    }

    private static String networkHint(String msg) {
        String m = msg != null ? msg : "";
        String lower = m.toLowerCase();
        if (lower.contains("cleartext") || lower.contains("clear text")) {
            return "无法使用明文 HTTP 访问服务器，请检查应用网络安全配置";
        }
        if (lower.contains("failed to connect") || lower.contains("connection refused")
                || lower.contains("connectexception") || lower.contains("enforced")) {
            return "连不上服务器 " + KgramServerConfig.CHAT_API + "，请检查安全组是否放行 10008，以及手机网络";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请稍后重试（检查 ECS 与安全组）";
        }
        if (lower.contains("unknownhost") || lower.contains("unable to resolve")) {
            return "无法解析服务器地址，请检查网络";
        }
        if (m.length() > 0) {
            return "网络错误：" + m;
        }
        return "网络错误，请检查网络后重试";
    }

    public static void registerAsync(String areaCode, String phoneNumber, String password, String nickname, Callback cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(register(areaCode, phoneNumber, password, nickname));
            } catch (Exception e) {
                cb.onDone(fromException(e));
            }
        });
    }

    public static void loginAsync(String areaCode, String phoneNumber, String password, Callback cb) {
        EXEC.execute(() -> {
            try {
                cb.onDone(login(areaCode, phoneNumber, password));
            } catch (Exception e) {
                cb.onDone(fromException(e));
            }
        });
    }

    private static Result fromException(Exception e) {
        String msg;
        if (e instanceof UnknownHostException) {
            msg = "UnknownHostException: " + e.getMessage();
        } else if (e instanceof SocketTimeoutException) {
            msg = "timeout: " + e.getMessage();
        } else if (e instanceof ConnectException) {
            msg = "ConnectException: " + e.getMessage();
        } else {
            msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        return new Result(ERR_NETWORK, msg, "", null, null, null, null);
    }

    public static Result register(String areaCode, String phoneNumber, String password, String nickname) throws Exception {
        String ac = normalizeAreaCode(areaCode);
        String phone = normalizePhone(phoneNumber);
        String nick = nickname != null ? nickname.trim() : "";
        if (nick.length() == 0) {
            nick = "kgram_" + phone;
        }

        JSONObject user = new JSONObject();
        user.put("nickname", nick);
        user.put("areaCode", ac);
        user.put("phoneNumber", phone);
        user.put("password", password != null ? password : "");

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
        body.put("areaCode", normalizeAreaCode(areaCode));
        body.put("phoneNumber", normalizePhone(phoneNumber));
        body.put("password", password != null ? password : "");
        return postJson(KgramServerConfig.CHAT_API + "/account/login", body);
    }

    private static Result postJson(String urlStr, JSONObject body) throws Exception {
        HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
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
                if (userID != null && userID.length() == 0) {
                    userID = null;
                }
                imToken = data.optString("imToken", null);
                if (imToken != null && imToken.length() == 0) {
                    imToken = null;
                }
                chatToken = data.optString("chatToken", null);
                if (chatToken != null && chatToken.length() == 0) {
                    chatToken = null;
                }
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
