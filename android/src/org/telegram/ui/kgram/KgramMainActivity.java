package org.telegram.ui.kgram;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import org.telegram.messenger.kgram.KgramSession;
import org.telegram.messenger.kgram.OpenIMImClient;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenIM main: TG DialogCell list + bottom tabs (Chats / Contacts / Settings).
 */
public class KgramMainActivity extends FragmentActivity {

    private static final int MENU_LOGOUT = 1;
    private static final int TAB_CHATS = 0;
    private static final int TAB_CONTACTS = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int TAB_H = 56;

    private final List<Row> chatRows = new ArrayList<>();
    private final List<Row> contactRows = new ArrayList<>();
    private DialogsAdapter adapter;
    private RecyclerListView listView;
    private TextView emptyView;
    private View progressView;
    private ActionBar actionBar;
    private FrameLayout settingsPage;
    private FragmentFloatingButton fab;
    private int currentTab = TAB_CHATS;
    private final TextView[] tabLabels = new TextView[3];
    private final ImageView[] tabIcons = new ImageView[3];

    private static final class Row {
        String peerUserID;
        String title;
        String subtitle;
        long timeMs;
        DialogCell.CustomDialog custom;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        try {
            AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());
            Theme.createDialogsResources(this);
        } catch (Throwable ignore) {
        }
        if (!KgramSession.hasSession(this)) {
            goLogin(true);
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        actionBar = new ActionBar(this) {
            @Override
            public void onDraw(Canvas canvas) {
                super.onDraw(canvas);
            }
        };
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("AppName", R.string.AppName));
        actionBar.setSubtitle(KgramSession.nickname(this) + " · " + KgramSession.userID(this));
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSelector), false);
        actionBar.setTitleColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
        actionBar.setSubtitleColor(Theme.getColor(Theme.key_actionBarDefaultSubtitle));
        actionBar.setCastShadows(true);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_LOGOUT, R.drawable.msg_leave);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == MENU_LOGOUT) {
                    KgramSession.clear(KgramMainActivity.this);
                    goLogin(true);
                }
            }
        });
        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        int top = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        int bottom = AndroidUtilities.dp(TAB_H);

        listView = new RecyclerListView(this);
        listView.setVerticalScrollBarEnabled(true);
        listView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        adapter = new DialogsAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            List<Row> data = currentTab == TAB_CONTACTS ? contactRows : chatRows;
            if (position < 0 || position >= data.size()) {
                return;
            }
            Row row = data.get(position);
            openChat(row.peerUserID, row.title);
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, top, 0, bottom));

        emptyView = new TextView(this);
        emptyView.setTextColor(Theme.getColor(Theme.key_emptyListPlaceholder));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), AndroidUtilities.dp(48));
        emptyView.setText(LocaleController.getString(R.string.NoChats) + "\n\n点右下角开始新对话");
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 0, top, 0, bottom));

        settingsPage = buildSettingsPage();
        settingsPage.setVisibility(View.GONE);
        root.addView(settingsPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, top, 0, bottom));

        progressView = new org.telegram.ui.Components.RadialProgressView(this);
        progressView.setVisibility(View.GONE);
        root.addView(progressView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));

        fab = new FragmentFloatingButton(this, null);
        fab.setImageResource(R.drawable.filled_fab_compose_32);
        fab.setContentDescription(LocaleController.getString(R.string.NewMessageTitle));
        fab.setOnClickListener(v -> showNewChatDialog());
        FrameLayout.LayoutParams fabLp = FragmentFloatingButton.createDefaultLayoutParams();
        fabLp.bottomMargin = AndroidUtilities.dp(TAB_H + 16);
        root.addView(fab, fabLp);

        root.addView(buildBottomTabs(), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, TAB_H, Gravity.BOTTOM));

        setContentView(root);
        selectTab(TAB_CHATS, false);
        AndroidUtilities.runOnUIThread(this::refresh, 50);
    }

    private FrameLayout buildSettingsPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        box.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        TextView name = new TextView(this);
        name.setText(KgramSession.nickname(this));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        box.addView(name);

        TextView uid = new TextView(this);
        uid.setText("userID  " + KgramSession.userID(this));
        uid.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        uid.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        uid.setPadding(0, AndroidUtilities.dp(6), 0, 0);
        box.addView(uid);

        TextView phone = new TextView(this);
        phone.setText("手机  " + KgramSession.phone(this));
        phone.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        phone.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        phone.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(16));
        box.addView(phone);

        TextView logout = new TextView(this);
        logout.setText("退出登录");
        logout.setGravity(Gravity.CENTER);
        logout.setTextColor(0xffffffff);
        logout.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        logout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8),
                Theme.getColor(Theme.key_text_RedBold), Theme.getColor(Theme.key_text_RedRegular)));
        logout.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        logout.setOnClickListener(v -> {
            KgramSession.clear(this);
            goLogin(true);
        });
        box.addView(logout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        page.addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 0, 0, 0));
        return page;
    }

    private LinearLayout buildBottomTabs() {
        LinearLayout bar = new LinearLayout(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                paint.setColor(Theme.getColor(Theme.key_divider));
                canvas.drawRect(0, 0, getWidth(), AndroidUtilities.dp(0.5f), paint);
            }
        };
        bar.setWillNotDraw(false);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        addTab(bar, TAB_CHATS, "聊天", R.drawable.msg_message);
        addTab(bar, TAB_CONTACTS, "联系人", R.drawable.msg_contacts);
        addTab(bar, TAB_SETTINGS, "设置", R.drawable.msg_settings);
        return bar;
    }

    private void addTab(LinearLayout bar, int id, String title, int iconRes) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        ImageView icon = new ImageView(this);
        try {
            Drawable d = getResources().getDrawable(iconRes).mutate();
            icon.setImageDrawable(d);
        } catch (Throwable t) {
            // ignore
        }
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        tab.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER));
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, AndroidUtilities.dp(2), 0, 0);
        tab.addView(label);
        tab.setOnClickListener(v -> selectTab(id, true));
        tabIcons[id] = icon;
        tabLabels[id] = label;
        bar.addView(tab, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
    }

    private void selectTab(int tab, boolean userClick) {
        currentTab = tab;
        int active = Theme.getColor(Theme.key_actionBarDefaultIcon);
        int inactive = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3);
        for (int i = 0; i < 3; i++) {
            int c = (i == tab) ? active : inactive;
            if (tabLabels[i] != null) {
                tabLabels[i].setTextColor(c);
            }
            if (tabIcons[i] != null) {
                tabIcons[i].setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
        boolean settings = tab == TAB_SETTINGS;
        settingsPage.setVisibility(settings ? View.VISIBLE : View.GONE);
        listView.setVisibility(settings ? View.GONE : View.VISIBLE);
        fab.setVisibility(tab == TAB_CHATS ? View.VISIBLE : View.GONE);
        if (tab == TAB_CHATS) {
            actionBar.setTitle(LocaleController.getString("AppName", R.string.AppName));
            bindList(chatRows, LocaleController.getString(R.string.NoChats) + "\n\n点右下角开始新对话");
        } else if (tab == TAB_CONTACTS) {
            actionBar.setTitle("联系人");
            bindList(contactRows, "暂无联系人\n在聊天里添加对方 userID 后会出现在这里");
        } else {
            actionBar.setTitle("设置");
            emptyView.setVisibility(View.GONE);
        }
        if (userClick && tab != TAB_SETTINGS) {
            refresh();
        }
    }

    private void bindList(List<Row> data, String emptyText) {
        adapter.setData(data);
        boolean empty = data.isEmpty();
        emptyView.setText(emptyText);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (KgramSession.hasSession(this)) {
            refresh();
        }
    }

    private void goLogin(boolean force) {
        Intent i = new Intent(this, LaunchActivity.class);
        if (force) {
            i.putExtra("kgram_force_login", true);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private void showNewChatDialog() {
        Context ctx = this;
        final EditTextBoldCursor input = new EditTextBoldCursor(ctx);
        input.setHint("对方 OpenIM userID");
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorSize(AndroidUtilities.dp(20));
        input.setCursorWidth(1.5f);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setBackgroundDrawable(Theme.createEditTextDrawable(ctx, false));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setMinHeight(AndroidUtilities.dp(48));
        input.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(LocaleController.getString(R.string.NewMessageTitle));
        builder.setMessage("输入对方手机上显示的 userID");
        builder.setView(input);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            String peer = input.getText().toString().trim();
            if (TextUtils.isEmpty(peer)) {
                Toast.makeText(this, "需要 userID", Toast.LENGTH_SHORT).show();
                return;
            }
            if (peer.equals(KgramSession.userID(this))) {
                Toast.makeText(this, "不能和自己聊", Toast.LENGTH_SHORT).show();
                return;
            }
            startChatWithPeer(peer);
        });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        });
        dialog.show();
        input.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
    }

    private void startChatWithPeer(final String peerUserID) {
        progressView.setVisibility(View.VISIBLE);
        final String me = KgramSession.userID(this);
        OpenIMImClient.ensureFriendAsync(me, peerUserID, r1 -> AndroidUtilities.runOnUIThread(() -> {
            OpenIMImClient.ensureFriendAsync(peerUserID, me, r2 -> AndroidUtilities.runOnUIThread(() -> {
                List<String> ids = new ArrayList<>();
                ids.add(peerUserID);
                OpenIMImClient.getUsersInfoAsync(ids, KgramSession.imToken(this), info -> AndroidUtilities.runOnUIThread(() -> {
                    progressView.setVisibility(View.GONE);
                    String name = peerUserID;
                    if (info.ok() && info.data != null && !info.data.isEmpty()) {
                        OpenIMImClient.UserInfo u = info.data.get(0);
                        if (u.nickname != null && u.nickname.length() > 0) {
                            name = u.nickname;
                        }
                    }
                    if (!r1.ok()) {
                        FileLog.e("kgram ensureFriend me->peer " + r1.errCode + " " + r1.errMsg);
                    }
                    openChat(peerUserID, name);
                    refresh();
                }));
            }));
        }));
    }

    private void openChat(String peerUserID, String title) {
        Intent i = new Intent(this, KgramChatActivity.class);
        i.putExtra(KgramChatActivity.EXTRA_PEER_ID, peerUserID);
        i.putExtra(KgramChatActivity.EXTRA_PEER_NAME, title != null ? title : peerUserID);
        startActivity(i);
    }

    private void refresh() {
        if (progressView != null && currentTab != TAB_SETTINGS) {
            progressView.setVisibility(View.VISIBLE);
        }
        final String me = KgramSession.userID(this);
        final String token = KgramSession.imToken(this);
        OpenIMImClient.getConversationsAsync(me, token, convRes -> {
            OpenIMImClient.getFriendsAsync(me, token, friendRes -> AndroidUtilities.runOnUIThread(() -> {
                if (progressView != null) {
                    progressView.setVisibility(View.GONE);
                }
                chatRows.clear();
                contactRows.clear();
                Map<String, Row> byPeer = new HashMap<>();
                if (convRes.ok() && convRes.data != null) {
                    for (OpenIMImClient.Conversation c : convRes.data) {
                        if (c.userID == null || c.userID.length() == 0) {
                            continue;
                        }
                        Row row = buildRow(c.userID,
                                (c.showName != null && c.showName.length() > 0) ? c.showName : c.userID,
                                c.latestMsgContent != null ? c.latestMsgContent : "",
                                c.latestMsgSendTime);
                        byPeer.put(c.userID, row);
                        chatRows.add(row);
                    }
                } else if (!convRes.ok()) {
                    FileLog.e("kgram convs " + convRes.errCode + " " + convRes.errMsg);
                }
                if (friendRes.ok() && friendRes.data != null) {
                    for (OpenIMImClient.Friend f : friendRes.data) {
                        if (f.userID == null) continue;
                        Row existing = byPeer.get(f.userID);
                        if (existing != null && f.nickname != null && f.nickname.length() > 0) {
                            existing.title = f.nickname;
                            existing.custom.name = f.nickname;
                        }
                        Row crow = buildRow(f.userID,
                                (f.nickname != null && f.nickname.length() > 0) ? f.nickname : f.userID,
                                f.userID,
                                0);
                        contactRows.add(crow);
                    }
                }
                java.util.Collections.sort(chatRows, (a, b) -> Long.compare(b.timeMs, a.timeMs));
                if (currentTab == TAB_CHATS) {
                    bindList(chatRows, LocaleController.getString(R.string.NoChats) + "\n\n点右下角开始新对话");
                } else if (currentTab == TAB_CONTACTS) {
                    bindList(contactRows, "暂无联系人\n在聊天里添加对方 userID 后会出现在这里");
                }
                if (actionBar != null) {
                    actionBar.setSubtitle(KgramSession.nickname(this) + " · " + me);
                }
            }));
        });
    }

    private Row buildRow(String peerUserID, String title, String subtitle, long timeMs) {
        Row row = new Row();
        row.peerUserID = peerUserID;
        row.title = title;
        row.subtitle = subtitle;
        row.timeMs = timeMs;
        DialogCell.CustomDialog custom = new DialogCell.CustomDialog();
        custom.id = peerUserID.hashCode();
        custom.name = title;
        custom.message = (subtitle != null && subtitle.length() > 0) ? subtitle : peerUserID;
        custom.date = timeMs > 0 ? (int) (timeMs / 1000L) : (int) (System.currentTimeMillis() / 1000L);
        custom.unread_count = 0;
        custom.pinned = false;
        custom.muted = false;
        custom.type = 0;
        custom.verified = false;
        custom.isMedia = false;
        custom.sent = -1;
        row.custom = custom;
        return row;
    }

    private class DialogsAdapter extends RecyclerListView.SelectionAdapter {
        private List<Row> data = chatRows;

        void setData(List<Row> rows) {
            data = rows;
            notifyDataSetChanged();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            DialogCell cell = new DialogCell(null, KgramMainActivity.this, false, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            DialogCell cell = (DialogCell) holder.itemView;
            Row row = data.get(position);
            cell.useSeparator = position != data.size() - 1;
            cell.setDialog(row.custom);
        }
    }
}
