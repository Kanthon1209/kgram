package org.telegram.ui.kgram;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.kgram.KgramServerConfig;
import org.telegram.messenger.kgram.KgramSession;
import org.telegram.messenger.kgram.OpenIMChatClient;
import org.telegram.messenger.kgram.KgramTgBridge;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

/**
 * Separate OpenIM register screen: phone + verify code + nickname + password.
 */
public class KgramRegisterActivity extends FragmentActivity {

    public static final String EXTRA_AREA = "area";
    public static final String EXTRA_PHONE = "phone";

    private String areaCode;
    private String phone;
    private EditTextBoldCursor codeField;
    private EditTextBoldCursor nickField;
    private EditTextBoldCursor passField;
    private EditTextBoldCursor pass2Field;
    private RadialProgressView progressView;
    private TextView nextBtn;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        try {
            AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());
            Theme.createCommonResources(this);
        } catch (Throwable ignore) {
        }

        areaCode = OpenIMChatClient.normalizeAreaCode(getIntent().getStringExtra(EXTRA_AREA));
        phone = OpenIMChatClient.normalizePhone(getIntent().getStringExtra(EXTRA_PHONE));
        if (TextUtils.isEmpty(areaCode) || TextUtils.isEmpty(phone)) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        ActionBar actionBar = new ActionBar(this);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("注册");
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSelector), false);
        actionBar.setTitleColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
        actionBar.setCastShadows(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finish();
                }
            }
        });
        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        TextView title = new TextView(this);
        title.setText("创建账号");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("手机号  " + areaCode + " " + phone);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
        content.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(label("验证码"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        codeField = field(false);
        codeField.setHint("演示默认 " + KgramServerConfig.SUPER_VERIFY_CODE);
        codeField.setText(KgramServerConfig.SUPER_VERIFY_CODE);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(codeField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 12));

        content.addView(label("昵称"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        nickField = field(false);
        nickField.setHint("怎么称呼你");
        nickField.setText("kgram_" + phone);
        content.addView(nickField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 12));

        content.addView(label("设置密码"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        passField = field(true);
        passField.setHint("至少 6 位");
        content.addView(passField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 12));

        content.addView(label("确认密码"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        pass2Field = field(true);
        pass2Field.setHint("再输入一次");
        pass2Field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pass2Field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doRegister();
                return true;
            }
            return false;
        });
        content.addView(pass2Field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        nextBtn = new TextView(this);
        nextBtn.setText("注册并进入");
        nextBtn.setGravity(Gravity.CENTER);
        nextBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nextBtn.setTypeface(AndroidUtilities.bold());
        nextBtn.setTextColor(0xffffffff);
        nextBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        nextBtn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
        nextBtn.setOnClickListener(v -> doRegister());
        content.addView(nextBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 0, 0));

        TextView goLogin = new TextView(this);
        goLogin.setText("已有账号？去登录");
        goLogin.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        goLogin.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        goLogin.setPadding(0, AndroidUtilities.dp(18), 0, 0);
        goLogin.setOnClickListener(v -> {
            Intent i = new Intent(this, KgramLoginActivity.class);
            i.putExtra(KgramLoginActivity.EXTRA_AREA, areaCode);
            i.putExtra(KgramLoginActivity.EXTRA_PHONE, phone);
            startActivity(i);
            finish();
        });
        content.addView(goLogin, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scroll.addView(content);
        int top = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, top, 0, 0));

        progressView = new RadialProgressView(this);
        progressView.setVisibility(android.view.View.GONE);
        root.addView(progressView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));

        setContentView(root);
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.setTypeface(AndroidUtilities.bold());
        v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return v;
    }

    private EditTextBoldCursor field(boolean password) {
        EditTextBoldCursor e = new EditTextBoldCursor(this);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        e.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setCursorSize(AndroidUtilities.dp(20));
        e.setCursorWidth(1.5f);
        e.setBackgroundDrawable(Theme.createEditTextDrawable(this, false));
        e.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(12), AndroidUtilities.dp(2), AndroidUtilities.dp(12));
        e.setSingleLine(true);
        e.setMinHeight(AndroidUtilities.dp(48));
        if (password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        return e;
    }

    private void doRegister() {
        if (busy) {
            return;
        }
        String code = codeField.getText() != null ? codeField.getText().toString().trim() : "";
        String nick = nickField.getText() != null ? nickField.getText().toString().trim() : "";
        String password = passField.getText() != null ? passField.getText().toString() : "";
        String password2 = pass2Field.getText() != null ? pass2Field.getText().toString() : "";
        if (!KgramServerConfig.SUPER_VERIFY_CODE.equals(code)) {
            alert("验证码错误", "演示环境请使用 " + KgramServerConfig.SUPER_VERIFY_CODE);
            return;
        }
        if (nick.length() == 0) {
            alert("提示", "请填写昵称");
            return;
        }
        if (password.length() < 6) {
            alert("提示", "密码至少 6 位");
            return;
        }
        if (!password.equals(password2)) {
            alert("提示", "两次密码不一致");
            return;
        }
        busy = true;
        progressView.setVisibility(android.view.View.VISIBLE);
        nextBtn.setEnabled(false);
        OpenIMChatClient.registerAsync(areaCode, phone, password, nick, result -> AndroidUtilities.runOnUIThread(() -> {
            if (result.ok()) {
                finishAuth(nick, result);
                return;
            }
            if (result.alreadyRegistered()) {
                // already registered → try login with same password
                OpenIMChatClient.loginAsync(areaCode, phone, password, result2 -> AndroidUtilities.runOnUIThread(() -> {
                    if (result2.ok()) {
                        finishAuth(nick, result2);
                        return;
                    }
                    busy = false;
                    progressView.setVisibility(android.view.View.GONE);
                    nextBtn.setEnabled(true);
                    if (result2.passwordError()) {
                        alert("账号已注册", "该手机号已注册，但密码不正确。请改用登录页。");
                    } else {
                        alert("注册失败", OpenIMChatClient.friendlyMessage(result2));
                    }
                }));
                return;
            }
            busy = false;
            progressView.setVisibility(android.view.View.GONE);
            nextBtn.setEnabled(true);
            alert("注册失败", OpenIMChatClient.friendlyMessage(result));
        }));
    }

    private void finishAuth(String nick, OpenIMChatClient.Result result) {
        busy = false;
        progressView.setVisibility(android.view.View.GONE);
        nextBtn.setEnabled(true);
        if (TextUtils.isEmpty(result.userID) || TextUtils.isEmpty(result.imToken)) {
            alert("注册异常", "服务器未返回 userID / imToken");
            return;
        }
        KgramSession.save(this, areaCode, phone, nick, result.userID, result.imToken, result.chatToken);
        KgramTgBridge.activateFromSession(this);
        Intent i = new Intent(this, LaunchActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(LocaleController.getString(R.string.OK), null).show();
    }
}
