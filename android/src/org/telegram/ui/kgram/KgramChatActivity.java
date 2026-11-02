package org.telegram.ui.kgram;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.kgram.KgramServerConfig;
import org.telegram.messenger.kgram.KgramSession;
import org.telegram.messenger.kgram.OpenIMImClient;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenIM 1:1 chat — TG ActionBar + SizeNotifierFrameLayout keyboard lift +
 * bubble cells using Theme chat colors. Composer stays above keyboard.
 */
public class KgramChatActivity extends FragmentActivity {

    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_PEER_NAME = "peer_name";

    private String peerId;
    private String peerName;
    private String myId;
    private String myNick;
    private String imToken;

    private final List<OpenIMImClient.ChatMessage> messages = new ArrayList<>();
    private MsgAdapter adapter;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private EditTextBoldCursor messageEditText;
    private FrameLayout inputContainer;
    private ActionBar actionBar;
    private SizeNotifierFrameLayout contentView;
    private int actionBarHeight;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;
    private String lastFingerprint = "";

    // Realtime via OpenIMWebSocket in KgramTgBridge; local poll disabled.
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            // Disabled: realtime is OpenIM WebSocket via KgramTgBridge.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        try {
            AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());
            Theme.createCommonResources(this);
            Theme.createChatResources(this, false);
        } catch (Throwable ignore) {
        }
        if (!KgramSession.hasSession(this)) {
            finish();
            return;
        }
        peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);
        if (TextUtils.isEmpty(peerId)) {
            finish();
            return;
        }
        if (TextUtils.isEmpty(peerName)) {
            peerName = peerId;
        }
        myId = KgramSession.userID(this);
        myNick = KgramSession.nickname(this);
        imToken = KgramSession.imToken(this);

        contentView = new SizeNotifierFrameLayout(this) {
            @Override
            protected boolean isActionBarVisible() {
                return true;
            }
        };
        try {
            contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        } catch (Throwable t) {
            contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        }

        actionBar = new ActionBar(this);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(peerName);
        actionBar.setSubtitle(peerId);
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSelector), false);
        actionBar.setTitleColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
        actionBar.setSubtitleColor(Theme.getColor(Theme.key_actionBarDefaultSubtitle));
        actionBar.setCastShadows(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finish();
                }
            }
        });
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        actionBarHeight = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;

        listView = new RecyclerListView(this);
        listView.setVerticalScrollBarEnabled(true);
        listView.setClipToPadding(false);
        layoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        adapter = new MsgAdapter();
        listView.setAdapter(adapter);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, actionBarHeight, 0, AndroidUtilities.dp(52)));

        inputContainer = new FrameLayout(this) {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                Drawable shadow = Theme.chat_composeShadowDrawable;
                if (shadow != null) {
                    int h = Math.max(shadow.getIntrinsicHeight(), AndroidUtilities.dp(2));
                    shadow.setBounds(0, 0, getMeasuredWidth(), h);
                    shadow.draw(canvas);
                }
                bgPaint.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground));
                canvas.drawRect(0, AndroidUtilities.dp(2), getMeasuredWidth(), getMeasuredHeight(), bgPaint);
            }
        };
        inputContainer.setWillNotDraw(false);
        inputContainer.setClickable(true);
        inputContainer.setPadding(0, AndroidUtilities.dp(2), 0, 0);

        ImageView emojiButton = new ImageView(this);
        emojiButton.setScaleType(ImageView.ScaleType.CENTER);
        try {
            Drawable smile = getResources().getDrawable(R.drawable.input_smile).mutate();
            smile.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.MULTIPLY));
            emojiButton.setImageDrawable(smile);
        } catch (Throwable t) {
            emojiButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.MULTIPLY));
        }
        inputContainer.addView(emojiButton, LayoutHelper.createFrame(48, 48, Gravity.BOTTOM | Gravity.LEFT));

        messageEditText = new EditTextBoldCursor(this);
        messageEditText.setHint(LocaleController.getString(R.string.TypeMessage));
        messageEditText.setHintTextColor(Theme.getColor(Theme.key_chat_messagePanelHint));
        // Force readable typed text (avoid invisible / theme-mismatch)
        int panelText = Theme.getColor(Theme.key_chat_messagePanelText);
        if (panelText == 0) {
            panelText = 0xff000000;
        }
        messageEditText.setTextColor(panelText);
        messageEditText.setCursorColor(Theme.getColor(Theme.key_chat_messagePanelCursor) != 0
                ? Theme.getColor(Theme.key_chat_messagePanelCursor) : panelText);
        messageEditText.setCursorSize(AndroidUtilities.dp(20));
        messageEditText.setCursorWidth(1.5f);
        messageEditText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        messageEditText.setBackgroundDrawable(null);
        messageEditText.setMaxLines(6);
        messageEditText.setMinLines(1);
        messageEditText.setSingleLine(false);
        messageEditText.setMinimumHeight(AndroidUtilities.dp(48));
        messageEditText.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12));
        messageEditText.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        messageEditText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        messageEditText.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        messageEditText.setIncludeFontPadding(true);
        messageEditText.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        inputContainer.addView(messageEditText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 48, 0, 48, 0));

        ImageView sendButton = new ImageView(this);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        Drawable sendDrawable = getResources().getDrawable(R.drawable.send_plane_24).mutate();
        sendDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelSend), PorterDuff.Mode.MULTIPLY));
        sendButton.setImageDrawable(sendDrawable);
        sendButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setOnClickListener(v -> sendMessage());
        inputContainer.addView(sendButton, LayoutHelper.createFrame(48, 48, Gravity.BOTTOM | Gravity.RIGHT));

        contentView.addView(inputContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM));

        // Lift composer above keyboard using SizeNotifierFrameLayout
        contentView.setDelegate((keyboardHeight, isWidthGreater) -> {
            int pad = Math.max(keyboardHeight, 0);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) inputContainer.getLayoutParams();
            if (lp.bottomMargin != pad) {
                lp.bottomMargin = pad;
                inputContainer.setLayoutParams(lp);
            }
            int composerH = inputContainer.getHeight() > 0 ? inputContainer.getHeight() : AndroidUtilities.dp(52);
            FrameLayout.LayoutParams listLp = (FrameLayout.LayoutParams) listView.getLayoutParams();
            int bottom = composerH + pad;
            if (listLp.bottomMargin != bottom || listLp.topMargin != actionBarHeight) {
                listLp.topMargin = actionBarHeight;
                listLp.bottomMargin = bottom;
                listView.setLayoutParams(listLp);
            }
            if (!messages.isEmpty()) {
                listView.post(() -> listView.scrollToPosition(messages.size() - 1));
            }
        });

        inputContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int h = v.getHeight();
            int pad = contentView.getKeyboardHeight();
            FrameLayout.LayoutParams listLp = (FrameLayout.LayoutParams) listView.getLayoutParams();
            int needBottom = h + Math.max(pad, 0);
            if (listLp.bottomMargin != needBottom) {
                listLp.bottomMargin = needBottom;
                listView.setLayoutParams(listLp);
            }
        });

        setContentView(contentView);
        OpenIMImClient.ensureFriendAsync(myId, peerId, r -> AndroidUtilities.runOnUIThread(() -> reload(true)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = false;
        handler.removeCallbacks(pollRunnable);
        reload(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        handler.removeCallbacks(pollRunnable);
    }

    private void sendMessage() {
        final String text = messageEditText.getText() != null ? messageEditText.getText().toString().trim() : "";
        if (text.length() == 0) {
            return;
        }
        messageEditText.setText("");
        if (actionBar != null) {
            actionBar.setSubtitle("sending…");
        }
        OpenIMImClient.sendTextAsync(myId, peerId, myNick, text, result -> AndroidUtilities.runOnUIThread(() -> {
            if (!result.ok()) {
                if (actionBar != null) {
                    actionBar.setSubtitle("send failed");
                }
                Toast.makeText(this, "Send failed " + result.errCode, Toast.LENGTH_SHORT).show();
                FileLog.e("kgram send " + result.errCode + " " + result.errMsg + " " + result.errDlt);
                return;
            }
            if (actionBar != null) {
                actionBar.setSubtitle(peerId);
            }
            reload(true);
        }));
    }

    private void reload(boolean forceScroll) {
        OpenIMImClient.searchHistoryAsync(myId, peerId, imToken, result -> AndroidUtilities.runOnUIThread(() -> {
            if (!result.ok() || result.data == null) {
                if (result.errCode != 0 && actionBar != null) {
                    actionBar.setSubtitle("load " + result.errCode);
                }
                return;
            }
            StringBuilder fp = new StringBuilder();
            for (OpenIMImClient.ChatMessage m : result.data) {
                fp.append(m.serverMsgID).append(':').append(m.seq).append(';');
            }
            String next = fp.toString();
            if (!forceScroll && next.equals(lastFingerprint)) {
                return;
            }
            lastFingerprint = next;
            messages.clear();
            messages.addAll(result.data);
            adapter.notifyDataSetChanged();
            if (!messages.isEmpty()) {
                listView.scrollToPosition(messages.size() - 1);
            }
            if (actionBar != null) {
                actionBar.setSubtitle(peerId);
            }
        }));
    }

    private class MsgAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            OpenIMImClient.ChatMessage m = messages.get(position);
            return myId.equals(m.sendID) ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new BubbleCell(KgramChatActivity.this, viewType == 1));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            BubbleCell cell = (BubbleCell) holder.itemView;
            OpenIMImClient.ChatMessage m = messages.get(position);
            cell.setText(m.text != null ? m.text : "");
        }
    }

    /** TG-colored round bubble with real TextView (typed/received text always visible). */
    private static class BubbleCell extends FrameLayout {
        private final TextView textView;
        private final boolean mine;

        public BubbleCell(android.content.Context context, boolean isMine) {
            super(context);
            mine = isMine;
            setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(3), AndroidUtilities.dp(10), AndroidUtilities.dp(3));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setTextColor(Theme.getColor(isMine ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn));
            textView.setLinkTextColor(Theme.getColor(isMine ? Theme.key_chat_messageLinkOut : Theme.key_chat_messageLinkIn));
            textView.setIncludeFontPadding(true);
            int padH = AndroidUtilities.dp(12);
            int padV = AndroidUtilities.dp(8);
            textView.setPadding(padH, padV, padH, padV);
            textView.setMaxWidth((int) (Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.dp(320)) * 0.78f));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.getColor(isMine ? Theme.key_chat_outBubble : Theme.key_chat_inBubble));
            float r = AndroidUtilities.dp(Math.max(SharedConfig.bubbleRadius, 10));
            bg.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
            textView.setBackground(bg);

            // Prefer official TG 9-patch if available
            try {
                Drawable d = isMine ? Theme.chat_msgOutDrawable : Theme.chat_msgInDrawable;
                if (d != null) {
                    textView.setBackground(d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d);
                }
            } catch (Throwable ignore) {
            }

            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    isMine ? (Gravity.RIGHT | Gravity.TOP) : (Gravity.LEFT | Gravity.TOP)));
        }

        public void setText(String text) {
            textView.setText(text);
        }
    }
}
