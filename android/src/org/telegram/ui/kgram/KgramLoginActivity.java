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
 * OpenIM login: phone (from previous page) + password. Register is secondary.
 */
public class KgramLoginActivity extends FragmentActivity {

    public static final String EXTRA_AREA = "area";
    public static final String EXTRA_PHONE = "phone";

    private String areaCode;
    private String phone;
    private EditTextBoldCursor passField;
    private TextView errorView;
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
        actionBar.setTitle("登录");
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
        title.setText("欢迎回来");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("账号  " + areaCode + " " + phone);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(20));
        content.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView passLabel = label("密码");
        content.addView(passLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        passField = field(true);
        passField.setHint("输入密码");
        passField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doLogin();
                return true;
            }
            return false;
        });
        content.addView(passField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        errorView = new TextView(this);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        errorView.setVisibility(android.view.View.GONE);
        errorView.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        content.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        nextBtn = new TextView(this);
        nextBtn.setText("登录");
        nextBtn.setGravity(Gravity.CENTER);
        nextBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nextBtn.setTypeface(AndroidUtilities.bold());
        nextBtn.setTextColor(0xffffffff);
        nextBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        nextBtn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
        nextBtn.setOnClickListener(v -> doLogin());
        content.addView(nextBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 0, 0));

        TextView goRegister = new TextView(this);
        goRegister.setText("没有账号？去注册");
        goRegister.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        goRegister.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        goRegister.setPadding(0, AndroidUtilities.dp(18), 0, 0);
        goRegister.setOnClickListener(v -> openRegister());
        content.addView(goRegister, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scroll.addView(content);
        int top = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, top, 0, 0));

        progressView = new RadialProgressView(this);
        progressView.setVisibility(android.view.View.GONE);
        root.addView(progressView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));

        setContentView(root);
        AndroidUtilities.runOnUIThread(() -> {
            passField.requestFocus();
            AndroidUtilities.showKeyboard(passField);
        }, 200);
    }

    private void openRegister() {
        Intent i = new Intent(this, KgramRegisterActivity.class);
        i.putExtra(KgramRegisterActivity.EXTRA_AREA, areaCode);
        i.putExtra(KgramRegisterActivity.EXTRA_PHONE, phone);
        startActivity(i);
        finish();
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
        }
        return e;
    }

    private void clearError() {
        if (errorView != null) {
            errorView.setVisibility(android.view.View.GONE);
            errorView.setText("");
        }
    }

    private void showFieldError(String msg) {
        if (errorView != null) {
            errorView.setText(msg);
            errorView.setVisibility(android.view.View.VISIBLE);
        }
    }

    private String resolveNickname(OpenIMChatClient.Result result) {
        if (result != null && !TextUtils.isEmpty(result.nickname)) {
            return result.nickname.trim();
        }
        String existing = KgramSession.nickname(this);
        if (!TextUtils.isEmpty(existing) && !existing.equals(KgramSession.userID(this))) {
            // keep a previously saved real nick when API did not return one
            if (!existing.startsWith("kgram_") || !existing.equals("kgram_" + phone)) {
                return existing;
            }
            // even phone-based fallback from prior session is better than inventing a new fake label
            return existing;
        }
        // Local display fallback only — not presented as a server-fetched nick.
        return "kgram_" + phone;
    }

    private void doLogin() {
        if (busy) {
            return;
        }
        clearError();
        String password = passField.getText() != null ? passField.getText().toString() : "";
        if (password.length() < 6) {
            showFieldError("密码至少 6 位");
            return;
        }
        busy = true;
        progressView.setVisibility(android.view.View.VISIBLE);
        nextBtn.setEnabled(false);
        nextBtn.setAlpha(0.5f);
        OpenIMChatClient.loginAsync(areaCode, phone, password, result -> AndroidUtilities.runOnUIThread(() -> {
            busy = false;
            progressView.setVisibility(android.view.View.GONE);
            nextBtn.setEnabled(true);
            nextBtn.setAlpha(1f);
            if (result.ok()) {
                if (TextUtils.isEmpty(result.userID) || TextUtils.isEmpty(result.imToken)) {
                    alert("登录异常", "服务器未返回 userID / imToken");
                    return;
                }
                String nick = resolveNickname(result);
                KgramSession.save(this, areaCode, phone, nick, result.userID, result.imToken, result.chatToken);
                KgramTgBridge.activateFromSession(this);
                Intent i = new Intent(this, LaunchActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                return;
            }
            if (result.accountNotFound()) {
                new AlertDialog.Builder(this)
                        .setTitle("尚未注册")
                        .setMessage("该手机号还没有账号。")
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton("去注册", (d, w) -> openRegister())
                        .show();
            } else {
                showFieldError(OpenIMChatClient.friendlyMessage(result));
            }
        }));
    }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(LocaleController.getString(R.string.OK), null).show();
    }
}
