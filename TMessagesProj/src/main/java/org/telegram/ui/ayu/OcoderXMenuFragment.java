package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionIntroActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.Cells.DividerCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.InviteContactsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.ayu.menu.OxDrawerProfileCell;
import org.telegram.ui.ayu.menu.OxDrawerUserCell;

import java.util.ArrayList;
import java.util.Collections;

/**
 * OcoderX-gram: the screen behind the "OcoderX" button next to "Chats". It replaces the floating
 * options popup with a full fragment laid out like the classic Telegram side drawer: the account
 * header (avatar / name / phone plus the account switcher), the classic action rows (night mode,
 * new group / channel / secret chat, contacts, calls, saved messages, settings, invite friends) and
 * an "OcoderX" section with this fork's own tool screens.
 * <p>
 * The feature switches of the fork stay where they were: in the "..." popup of the chats screen
 * ({@code DialogsActivity.addOcoderXFeatureOptions}).
 */
public class OcoderXMenuFragment extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int VIEW_TYPE_PROFILE = 0;
    private static final int VIEW_TYPE_ACTION = 1;
    private static final int VIEW_TYPE_CHECK = 2;
    private static final int VIEW_TYPE_DIVIDER = 3;
    private static final int VIEW_TYPE_HEADER = 4;
    private static final int VIEW_TYPE_ACCOUNT = 5;
    private static final int VIEW_TYPE_SHADOW = 6;

    private static final int ID_NONE = 0;
    private static final int ID_NIGHT_MODE = 1;
    private static final int ID_NEW_GROUP = 2;
    private static final int ID_NEW_CHANNEL = 3;
    private static final int ID_NEW_SECRET = 4;
    private static final int ID_CONTACTS = 5;
    private static final int ID_CALLS = 6;
    private static final int ID_SAVED_MESSAGES = 7;
    private static final int ID_SETTINGS = 8;
    private static final int ID_INVITE = 9;
    private static final int ID_ADD_ACCOUNT = 10;
    private static final int ID_SWITCH_ACCOUNT = 11;
    private static final int ID_DOWNLOAD_MANAGER = 12;
    private static final int ID_FILE_EXPLORER = 13;
    private static final int ID_SCREEN_LIGHT = 14;
    private static final int ID_ID_FINDER = 15;
    private static final int ID_ONLINE_CONTACTS = 16;
    private static final int ID_SPECIAL_CONTACTS = 17;
    private static final int ID_CONTACT_TRACKER = 18;
    private static final int ID_CONTACT_CHANGES = 19;
    private static final int ID_DUPLICATES = 20;
    private static final int ID_SYNC = 21;
    private static final int ID_ALL_SETTINGS = 22;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<Item> items = new ArrayList<>();
    private boolean accountsExpanded;

    /** Opens the download manager of the chats screen; supplied by {@code MainTabsActivity}. */
    private Runnable downloadManagerAction;

    /** true when this screen is a page of {@link MainTabsActivity} instead of a pushed fragment. */
    private boolean hasMainTabs;
    private int navigationBarHeight = AndroidUtilities.navigationBarHeight;
    private int statusBarHeight = AndroidUtilities.statusBarHeight;

    public OcoderXMenuFragment() {
        super();
    }

    public OcoderXMenuFragment(Bundle args) {
        super(args);
    }

    public void setDownloadManagerAction(Runnable action) {
        downloadManagerAction = action;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        getNotificationCenter().addObserver(this, NotificationCenter.mainUserInfoChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.mainUserInfoChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        if (!hasMainTabs) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OxTabOcoderX));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && !hasMainTabs) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));
        listView.setOnItemClickListener((view, position) -> onItemClick(view, position));

        if (hasMainTabs) {
            // the action bar overlays the page and the floating tabs bar covers its bottom
            listView.setClipToPadding(false);
            checkUi_listViewPadding();
            ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onApplyWindowInsets);
        }

        updateRows();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRows();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.updateInterfaces) {
            //perf: updateInterfaces fires for every online-status / typing / read update; this page
            // only shows names, avatars, phones and emoji statuses, so everything else is ignored
            // instead of rebinding the whole list
            final int mask = args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            if ((mask & (MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_AVATAR
                    | MessagesController.UPDATE_MASK_PHONE | MessagesController.UPDATE_MASK_USER_PHONE
                    | MessagesController.UPDATE_MASK_EMOJI_STATUS)) == 0) {
                return;
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    // ------------------------------------------------------------------ rows

    private static class Item {
        final int viewType;
        final int id;
        final int icon;
        final CharSequence text;
        final int account;

        private Item(int viewType, int id, int icon, CharSequence text, int account) {
            this.viewType = viewType;
            this.id = id;
            this.icon = icon;
            this.text = text;
            this.account = account;
        }

        static Item profile() {
            return new Item(VIEW_TYPE_PROFILE, ID_NONE, 0, null, 0);
        }

        static Item account(int account) {
            return new Item(VIEW_TYPE_ACCOUNT, ID_SWITCH_ACCOUNT, 0, null, account);
        }

        static Item action(int id, int icon, CharSequence text) {
            return new Item(VIEW_TYPE_ACTION, id, icon, text, 0);
        }

        static Item check(int id, int icon, CharSequence text) {
            return new Item(VIEW_TYPE_CHECK, id, icon, text, 0);
        }

        static Item divider() {
            return new Item(VIEW_TYPE_DIVIDER, ID_NONE, 0, null, 0);
        }

        static Item header(CharSequence text) {
            return new Item(VIEW_TYPE_HEADER, ID_NONE, 0, text, 0);
        }

        static Item shadow() {
            return new Item(VIEW_TYPE_SHADOW, ID_NONE, 0, null, 0);
        }
    }

    private void updateRows() {
        items.clear();
        items.add(Item.profile());

        if (accountsExpanded) {
            for (int account : activatedAccounts()) {
                items.add(Item.account(account));
            }
            if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
                items.add(Item.action(ID_ADD_ACCOUNT, R.drawable.msg_addbot, getString(R.string.AddAccount)));
            }
            items.add(Item.divider());
        }

        items.add(Item.check(ID_NIGHT_MODE, R.drawable.menu_night_mode_24, getString(R.string.SwitchThemeToNight)));
        items.add(Item.divider());

        final boolean bot = getUserConfig().isBotAccount();
        if (!bot) {
            // a bot can neither create chats nor write to itself, and has no contacts of its own
            items.add(Item.action(ID_NEW_GROUP, R.drawable.msg_groups, getString(R.string.NewGroup)));
            items.add(Item.action(ID_NEW_CHANNEL, R.drawable.msg_channel, getString(R.string.NewChannel)));
            items.add(Item.action(ID_NEW_SECRET, R.drawable.msg_secret, getString(R.string.NewSecretChat)));
            items.add(Item.divider());
            items.add(Item.action(ID_CONTACTS, R.drawable.msg_contacts, getString(R.string.Contacts)));
            items.add(Item.action(ID_CALLS, R.drawable.msg_calls, getString(R.string.Calls)));
            items.add(Item.action(ID_SAVED_MESSAGES, R.drawable.msg_saved, getString(R.string.SavedMessages)));
        }
        items.add(Item.action(ID_SETTINGS, R.drawable.msg_settings, getString(R.string.Settings)));
        if (!bot) {
            items.add(Item.divider());
            items.add(Item.action(ID_INVITE, R.drawable.settings_invite, getString(R.string.InviteFriends)));
        }

        items.add(Item.header(getString(R.string.OxTabOcoderX)));
        if (downloadManagerAction != null) {
            items.add(Item.action(ID_DOWNLOAD_MANAGER, R.drawable.msg_download, getString(R.string.OxMenuDownloadManager)));
        }
        items.add(Item.action(ID_FILE_EXPLORER, R.drawable.msg_folders, getString(R.string.AyuDrawerFileExplorer)));
        items.add(Item.action(ID_SCREEN_LIGHT, R.drawable.msg_brightness_high, getString(R.string.OxMenuScreenLight)));
        items.add(Item.divider());
        items.add(Item.action(ID_ID_FINDER, R.drawable.msg_mention, getString(R.string.OxMenuIdFinder)));
        items.add(Item.action(ID_ONLINE_CONTACTS, R.drawable.msg_online, getString(R.string.OxMenuOnlineContacts)));
        items.add(Item.action(ID_SPECIAL_CONTACTS, R.drawable.msg_fave, getString(R.string.OxMenuSpecialContact)));
        items.add(Item.action(ID_CONTACT_TRACKER, R.drawable.msg_recent, getString(R.string.OxMenuContactTracker)));
        items.add(Item.action(ID_CONTACT_CHANGES, R.drawable.msg_contacts, getString(R.string.OxMenuContactsChanges)));
        items.add(Item.action(ID_DUPLICATES, R.drawable.msg_copy, getString(R.string.AyuDrawerDuplicates)));
        items.add(Item.action(ID_SYNC, R.drawable.msg_retry, getString(R.string.AyuDrawerSync)));
        items.add(Item.action(ID_ALL_SETTINGS, R.drawable.msg_settings, getString(R.string.AyuDrawerAllSettings)));
        items.add(Item.shadow());

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private ArrayList<Integer> activatedAccounts() {
        final ArrayList<Integer> accountNumbers = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            return Long.compare(l1, l2);
        });
        return accountNumbers;
    }

    // ------------------------------------------------------------------ clicks

    private void onItemClick(View view, int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        final Item item = items.get(position);
        if (item.viewType == VIEW_TYPE_PROFILE) {
            accountsExpanded = !accountsExpanded;
            updateRows();
            return;
        }
        if (item.viewType == VIEW_TYPE_ACCOUNT) {
            if (item.account != currentAccount && LaunchActivity.instance != null) {
                LaunchActivity.instance.switchToAccount(item.account, true);
            } else {
                accountsExpanded = false;
                updateRows();
            }
            return;
        }
        switch (item.id) {
            case ID_NIGHT_MODE:
                toggleNightMode(view);
                break;
            case ID_ADD_ACCOUNT:
                openAddAccount();
                break;
            case ID_NEW_GROUP: {
                presentFragment(new GroupCreateActivity(new Bundle()));
                break;
            }
            case ID_NEW_CHANNEL: {
                final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                if (!BuildVars.DEBUG_VERSION && preferences.getBoolean("channel_intro", false)) {
                    Bundle args = new Bundle();
                    args.putInt("step", 0);
                    presentFragment(new ChannelCreateActivity(args));
                } else {
                    presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
                    preferences.edit().putBoolean("channel_intro", true).commit();
                }
                break;
            }
            case ID_NEW_SECRET: {
                Bundle args = new Bundle();
                args.putBoolean("onlyUsers", true);
                args.putBoolean("destroyAfterSelect", true);
                args.putBoolean("createSecretChat", true);
                args.putBoolean("allowBots", false);
                args.putBoolean("allowSelf", false);
                presentFragment(new ContactsActivity(args));
                break;
            }
            case ID_CONTACTS:
                presentFragment(new ContactsActivity(null));
                break;
            case ID_CALLS:
                presentFragment(new CallLogActivity());
                break;
            case ID_SAVED_MESSAGES: {
                Bundle args = new Bundle();
                args.putLong("user_id", getUserConfig().getClientUserId());
                presentFragment(new ChatActivity(args));
                break;
            }
            case ID_SETTINGS:
                presentFragment(new SettingsActivity());
                break;
            case ID_INVITE:
                presentFragment(new InviteContactsActivity());
                break;
            case ID_DOWNLOAD_MANAGER:
                openDownloadManager();
                break;
            case ID_FILE_EXPLORER:
                presentFragment(new org.telegram.ui.ayu.explorer.FileExplorerActivity());
                break;
            case ID_SCREEN_LIGHT:
                presentFragment(new org.telegram.ui.ayu.tools.ScreenLightActivity());
                break;
            case ID_ID_FINDER:
                presentFragment(new org.telegram.ui.ayu.tools.IdFinderActivity());
                break;
            case ID_ONLINE_CONTACTS:
                presentFragment(new org.telegram.ui.ayu.contacts.OnlineContactsActivity());
                break;
            case ID_SPECIAL_CONTACTS:
                presentFragment(new org.telegram.ui.ayu.contacts.SpecialContactsActivity());
                break;
            case ID_CONTACT_TRACKER:
                presentFragment(new org.telegram.ui.ayu.contacts.ContactTrackerActivity());
                break;
            case ID_CONTACT_CHANGES:
                presentFragment(new org.telegram.ui.ayu.contactchanges.ContactChangesActivity());
                break;
            case ID_DUPLICATES:
                presentFragment(new org.telegram.ui.ayu.duplicates.DuplicateCleanerActivity());
                break;
            case ID_SYNC:
                presentFragment(new AyuSyncPreferencesActivity());
                break;
            case ID_ALL_SETTINGS:
                presentFragment(new AyuPreferencesActivity());
                break;
        }
    }

    /**
     * The download manager lives inside the chats screen (its search view opened on the downloads
     * tab), so this screen closes itself first and lets {@code MainTabsActivity} run the action on
     * the {@link DialogsActivity} instance that is now visible again.
     */
    private void openDownloadManager() {
        final Runnable action = downloadManagerAction;
        if (action == null) {
            return;
        }
        if (hasMainTabs) {
            final MainTabsActivity mainTabs = getMainTabsActivity();
            if (mainTabs != null) {
                mainTabs.selectChatsTab();
            }
        } else {
            finishFragment();
        }
        AndroidUtilities.runOnUIThread(action, 200);
    }

    private MainTabsActivity getMainTabsActivity() {
        final org.telegram.ui.ActionBar.INavigationLayout layout = getParentLayout();
        final BaseFragment last = layout != null ? layout.getLastFragment() : null;
        return last instanceof MainTabsActivity ? (MainTabsActivity) last : null;
    }

    private void checkUi_listViewPadding() {
        if (listView == null || !hasMainTabs) {
            return;
        }
        listView.setPadding(
            0,
            statusBarHeight + ActionBar.getCurrentActionBarHeight(),
            0,
            navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS)
        );
    }

    @NonNull
    private WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);
        navigationBarHeight = systemInsets.bottom;
        statusBarHeight = systemInsets.top;
        checkUi_listViewPadding();
        return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // the page never scrolls sideways itself, so the tabs pager always takes the gesture
        return true;
    }

    private void toggleNightMode(View view) {
        if (DialogsActivity.switchingTheme) {
            return;
        }
        DialogsActivity.switchingTheme = true;

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Activity.MODE_PRIVATE);
        String dayThemeName = preferences.getString("lastDayTheme", "Blue");
        if (Theme.getTheme(dayThemeName) == null || Theme.getTheme(dayThemeName).isDark()) {
            dayThemeName = "Blue";
        }
        String nightThemeName = preferences.getString("lastDarkTheme", "Dark Blue");
        if (Theme.getTheme(nightThemeName) == null || !Theme.getTheme(nightThemeName).isDark()) {
            nightThemeName = "Dark Blue";
        }
        Theme.ThemeInfo themeInfo = Theme.getActiveTheme();
        if (dayThemeName.equals(nightThemeName)) {
            if (themeInfo.isDark() || dayThemeName.equals("Dark Blue") || dayThemeName.equals("Night")) {
                dayThemeName = "Blue";
            } else {
                nightThemeName = "Dark Blue";
            }
        }

        final boolean toDark;
        if (toDark = dayThemeName.equals(themeInfo.getKey())) {
            themeInfo = Theme.getTheme(nightThemeName);
        } else {
            themeInfo = Theme.getTheme(dayThemeName);
        }

        if (view instanceof TextCell) {
            ((TextCell) view).setChecked(toDark);
        }

        final int[] pos = new int[2];
        view.getLocationInWindow(pos);
        pos[0] += view.getMeasuredWidth() / 2;
        pos[1] += view.getMeasuredHeight() / 2;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme,
                themeInfo, false, pos, -1, toDark, null, null, null, true);

        Theme.turnOffAutoNight(BulletinFactory.of(this), () -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_NIGHT)));
    }

    /** Opens the login screen in a free account slot, exactly like the classic "Add account" row. */
    private void openAddAccount() {
        int freeAccounts = 0;
        Integer availableAccount = null;
        for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                freeAccounts++;
                if (availableAccount == null) {
                    availableAccount = a;
                }
            }
        }
        if (!UserConfig.hasPremiumOnAccounts()) {
            freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);
        }
        if (freeAccounts > 0 && availableAccount != null) {
            presentFragment(new LoginActivity(availableAccount));
        } else if (!UserConfig.hasPremiumOnAccounts() && getContext() != null) {
            showDialog(new LimitReachedBottomSheet(this, getContext(), TYPE_ACCOUNTS, currentAccount, null));
        }
    }

    // ------------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_PROFILE || viewType == VIEW_TYPE_ACTION
                    || viewType == VIEW_TYPE_CHECK || viewType == VIEW_TYPE_ACCOUNT;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            switch (viewType) {
                case VIEW_TYPE_PROFILE:
                    view = new OxDrawerProfileCell(context);
                    break;
                case VIEW_TYPE_ACCOUNT:
                    view = new OxDrawerUserCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_DIVIDER:
                    view = new DividerCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view.setPadding(dp(24), dp(4), dp(24), dp(4));
                    break;
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                case VIEW_TYPE_CHECK:
                default: {
                    TextCell cell = new TextCell(context, 23, false, viewType == VIEW_TYPE_CHECK, null);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Item item = items.get(position);
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_PROFILE:
                    ((OxDrawerProfileCell) holder.itemView).setAccount(currentAccount, accountsExpanded);
                    break;
                case VIEW_TYPE_ACCOUNT:
                    ((OxDrawerUserCell) holder.itemView).setAccount(item.account, item.account == currentAccount);
                    break;
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(item.text);
                    break;
                case VIEW_TYPE_CHECK: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndCheckAndIcon(item.text, Theme.isCurrentThemeDark(), item.icon, false);
                    cell.setColors(Theme.key_chats_menuItemIcon, Theme.key_chats_menuItemText);
                    break;
                }
                case VIEW_TYPE_ACTION: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndIcon(item.text, item.icon, false);
                    cell.setColors(Theme.key_chats_menuItemIcon, Theme.key_chats_menuItemText);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).viewType;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
