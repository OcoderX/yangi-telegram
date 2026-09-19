package org.telegram.ui.ayu.radar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.radar.MentionRadar;
import org.telegram.messenger.ayu.radar.RadarConfig;
import org.telegram.messenger.ayu.radar.RadarHit;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

/**
 * AyuGram Mention Radar — a single feed of every message that mentioned me, replied to me, carried
 * my name or one of my keywords, across all groups and channels.
 */
public class MentionRadarActivity extends BaseFragment implements MentionRadar.RadarDelegate {

    private static final int MENU_OTHER = 100;
    private static final int MENU_MARK_ALL_READ = 1;
    private static final int MENU_SCAN = 2;
    private static final int MENU_CANCEL_SCAN = 3;
    private static final int MENU_KEYWORDS = 4;
    private static final int MENU_CLEAR = 5;

    private static final int TAB_ALL = 0;
    private static final int TAB_MENTIONS = 1;
    private static final int TAB_REPLIES = 2;
    private static final int TAB_NAME = 3;
    private static final int TAB_KEYWORDS = 4;

    private static final int PAGE_SIZE = 80;

    private ScrollSlidingTextTabStrip tabStrip;
    private RecyclerListView listView;
    private ListAdapter adapter;
    private EmptyTextProgressView emptyView;
    private LinearLayoutManager layoutManager;
    private ActionBarMenuItem otherItem;

    private final ArrayList<RadarHit> hits = new ArrayList<>();
    private int currentKind = RadarHit.FILTER_ALL;
    private boolean loading;
    private boolean endReached;
    private boolean scanning;

    private MentionRadar radar() {
        return MentionRadar.getInstance(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        RadarConfig.load();
        radar().start();
        radar().addDelegate(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        radar().removeDelegate(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.RadarTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_MARK_ALL_READ) {
                    radar().markAllRead();
                    for (int i = 0; i < hits.size(); i++) {
                        hits.get(i).read = true;
                    }
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                } else if (id == MENU_SCAN) {
                    startScan();
                } else if (id == MENU_CANCEL_SCAN) {
                    radar().cancelScan();
                } else if (id == MENU_KEYWORDS) {
                    presentFragment(new RadarKeywordsActivity());
                } else if (id == MENU_CLEAR) {
                    showClearDialog();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        otherItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        otherItem.addSubItem(MENU_MARK_ALL_READ, R.drawable.msg_markread, getString(R.string.RadarMarkAllRead));
        otherItem.addSubItem(MENU_SCAN, R.drawable.msg_search, getString(R.string.RadarScanHistory));
        otherItem.addSubItem(MENU_CANCEL_SCAN, R.drawable.msg_delete, getString(R.string.RadarScanCancel));
        otherItem.addSubItem(MENU_KEYWORDS, R.drawable.msg_customize, getString(R.string.RadarKeywords));
        otherItem.addSubItem(MENU_CLEAR, R.drawable.msg_delete, getString(R.string.RadarClearAll));
        otherItem.hideSubItem(MENU_CANCEL_SCAN);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        final int gravity = Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

        tabStrip = new ScrollSlidingTextTabStrip(context, getResourceProvider());
        tabStrip.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        frameLayout.addView(tabStrip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, gravity, 0, 0, 0, 0));

        View divider = new View(context);
        divider.setBackgroundColor(getThemedColor(Theme.key_divider));
        frameLayout.addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, gravity, 0, 44, 0, 0));

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.RadarEmpty));
        emptyView.showProgress();
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, gravity, 0, 44, 0, 0));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setEmptyView(emptyView);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, gravity, 0, 44, 0, 0));

        listView.setOnItemClickListener((view, position) -> {
            RadarHit hit = hitAt(position);
            if (hit != null) {
                openHit(hit);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            RadarHit hit = hitAt(position);
            if (hit == null) {
                return false;
            }
            showOptions(view, hit);
            return true;
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (loading || endReached) {
                    return;
                }
                int lastVisible = layoutManager.findLastVisibleItemPosition();
                if (lastVisible >= hits.size() - 10) {
                    loadPage(false);
                }
            }
        });

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                RadarHit hit = hitAt(position);
                if (hit == null) {
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    return;
                }
                deleteHit(hit, position);
            }
        }).attachToRecyclerView(listView);

        buildTabs();
        loadPage(true);
        updateSubtitle();
        return fragmentView;
    }

    private void buildTabs() {
        tabStrip.setUseSameWidth(false);
        tabStrip.addTextTab(TAB_ALL, getString(R.string.RadarFilterAll));
        tabStrip.addTextTab(TAB_MENTIONS, getString(R.string.RadarFilterMentions));
        tabStrip.addTextTab(TAB_REPLIES, getString(R.string.RadarFilterReplies));
        tabStrip.addTextTab(TAB_NAME, getString(R.string.RadarFilterName));
        tabStrip.addTextTab(TAB_KEYWORDS, getString(R.string.RadarFilterKeywords));
        tabStrip.finishAddingTabs();
        tabStrip.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override
            public void onPageSelected(int page, boolean forward) {
                int kind = kindForTab(page);
                if (kind == currentKind) {
                    return;
                }
                currentKind = kind;
                loadPage(true);
            }

            @Override
            public void onPageScrolled(float progress) {
            }
        });
    }

    private static int kindForTab(int tab) {
        switch (tab) {
            case TAB_MENTIONS:
                return RadarHit.KIND_MENTION;
            case TAB_REPLIES:
                return RadarHit.KIND_REPLY;
            case TAB_NAME:
                return RadarHit.KIND_NAME;
            case TAB_KEYWORDS:
                return RadarHit.KIND_KEYWORD;
            case TAB_ALL:
            default:
                return RadarHit.FILTER_ALL;
        }
    }

    // ------------------------------------------------------------------ data

    private RadarHit hitAt(int position) {
        if (position < 0 || position >= hits.size()) {
            return null;
        }
        return hits.get(position);
    }

    private void loadPage(boolean reset) {
        if (loading) {
            return;
        }
        loading = true;
        if (reset) {
            endReached = false;
            hits.clear();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (emptyView != null) {
                emptyView.showProgress();
            }
        }
        final int kind = currentKind;
        final int offset = reset ? 0 : hits.size();
        radar().loadHits(kind, PAGE_SIZE, offset, result -> {
            loading = false;
            if (kind != currentKind) {
                return;
            }
            if (result == null || result.isEmpty()) {
                endReached = true;
            } else {
                if (offset == 0) {
                    hits.clear();
                }
                hits.addAll(result);
                endReached = result.size() < PAGE_SIZE;
            }
            if (emptyView != null) {
                emptyView.showTextView();
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateSubtitle();
        });
    }

    private void updateSubtitle() {
        if (actionBar == null) {
            return;
        }
        if (scanning) {
            return;
        }
        int unread = radar().getUnreadCount();
        if (unread > 0) {
            actionBar.setSubtitle(LocaleController.formatString(R.string.RadarUnread, unread));
        } else {
            actionBar.setSubtitle(null);
        }
    }

    // ------------------------------------------------------------------ actions

    private void openHit(RadarHit hit) {
        if (hit.dialogId == 0) {
            return;
        }
        if (!hit.read) {
            hit.read = true;
            radar().markRead(hit.rowId);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
        Bundle args = new Bundle();
        long dialogId = hit.dialogId;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            long did = dialogId;
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
            if (chat != null && chat.migrated_to != null) {
                args.putLong("migrated_to", did);
                did = -chat.migrated_to.channel_id;
            }
            args.putLong("chat_id", -did);
        }
        if (hit.msgId != 0) {
            args.putInt("message_id", hit.msgId);
        }
        if (!MessagesController.getInstance(currentAccount).checkCanOpenChat(args, this)) {
            return;
        }
        ChatActivity chatActivity = new ChatActivity(args);
        radar().inflate(hit);
        if (hit.messageObject != null) {
            presentFragment(DialogsActivity.highlightFoundQuote(chatActivity, hit.messageObject));
        } else {
            presentFragment(chatActivity);
        }
    }

    private void showOptions(View view, RadarHit hit) {
        ItemOptions options = ItemOptions.makeOptions(this, view);
        if (!hit.read) {
            options.add(R.drawable.msg_markread, getString(R.string.RadarMarkRead), () -> {
                hit.read = true;
                radar().markRead(hit.rowId);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }
        options.add(R.drawable.msg_message, getString(R.string.RadarOpenChat), () -> openHit(hit));
        options.add(R.drawable.msg_delete, getString(R.string.RadarDelete), true, () -> deleteHit(hit, hits.indexOf(hit)));
        options.setGravity(Gravity.RIGHT);
        options.show();
    }

    private void deleteHit(RadarHit hit, int position) {
        radar().deleteHit(hit.rowId);
        if (position >= 0 && position < hits.size() && hits.get(position) == hit) {
            hits.remove(position);
            if (adapter != null) {
                adapter.notifyItemRemoved(position);
                adapter.notifyItemRangeChanged(position, Math.max(0, hits.size() - position));
            }
        } else {
            hits.remove(hit);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
        updateSubtitle();
    }

    private void showClearDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.RadarClearAll));
        builder.setMessage(getString(R.string.RadarClearAllMessage));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            radar().clearAll();
            hits.clear();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateSubtitle();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            ((android.widget.TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void startScan() {
        if (scanning) {
            return;
        }
        int modes = MentionRadar.SCAN_MENTIONS;
        if (RadarConfig.matchKeywords && RadarConfig.hasUsableKeywords()) {
            modes |= MentionRadar.SCAN_KEYWORDS;
        }
        scanning = true;
        if (otherItem != null) {
            otherItem.hideSubItem(MENU_SCAN);
            otherItem.showSubItem(MENU_CANCEL_SCAN);
        }
        actionBar.setSubtitle(getString(R.string.RadarScanStarting));
        radar().startScan(modes);
    }

    // ------------------------------------------------------------------ radar delegate

    @Override
    public void onRadarHitsChanged(int account, int newHits) {
        if (account != currentAccount) {
            return;
        }
        updateSubtitle();
        if (newHits > 0 && !loading) {
            loadPage(true);
        }
    }

    @Override
    public void onRadarScanProgress(int account, int done, int total, int found) {
        if (account != currentAccount || actionBar == null) {
            return;
        }
        actionBar.setSubtitle(LocaleController.formatString(R.string.RadarScanProgress, done, total, found));
    }

    @Override
    public void onRadarScanFinished(int account, int found, boolean cancelled, String error) {
        if (account != currentAccount) {
            return;
        }
        scanning = false;
        if (otherItem != null) {
            otherItem.showSubItem(MENU_SCAN);
            otherItem.hideSubItem(MENU_CANCEL_SCAN);
        }
        updateSubtitle();
        if (getParentActivity() == null) {
            return;
        }
        if (cancelled) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.RadarScanCancelled)).show();
        } else if (error != null) {
            BulletinFactory.of(this).createErrorBulletin(error).show();
        } else {
            BulletinFactory.of(this).createSuccessBulletin(LocaleController.formatString(R.string.RadarScanDone, found)).show();
        }
        loadPage(true);
    }

    // ------------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            RadarHitCell cell = new RadarHitCell(parent.getContext(), currentAccount);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            RadarHit hit = hitAt(position);
            if (hit == null) {
                return;
            }
            ((RadarHitCell) holder.itemView).setHit(hit, position != hits.size() - 1);
        }

        @Override
        public int getItemCount() {
            return hits.size();
        }
    }
}
