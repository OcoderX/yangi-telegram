package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.OxSyncConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Ox-gram: the "OcoderX-sync" screen.
 * <p>
 * It explains the paid OcoderX-sync server service in plain words, lets the user order it from
 * {@link #ORDER_USERNAME} in one tap and stores the three userbot credentials that OcoderX sends
 * back (API ID, API hash, session string) in {@link OxSyncConfig}. The userbot engine itself is
 * implemented elsewhere; this screen only collects and shows the credentials.
 */
public class AyuSyncPreferencesActivity extends BaseFragment {

    /** Telegram account that sells and activates the service. Change here if it ever moves. */
    public static final String ORDER_USERNAME = "OcoderX";

    private static final int ID_ORDER = 1;
    private static final int ID_API_ID = 2;
    private static final int ID_API_HASH = 3;
    private static final int ID_SESSION = 4;
    private static final int ID_STATUS = 5;
    private static final int ID_CLEAR = 6;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_SHADOW = 1;
    private static final int VIEW_TYPE_SETTINGS = 2;
    private static final int VIEW_TYPE_BUTTON = 3;
    private static final int VIEW_TYPE_BULLET = 4;
    private static final int VIEW_TYPE_HERO = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<ItemInner> oldItems = new ArrayList<>(), items = new ArrayList<>();

    public AyuSyncPreferencesActivity() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        OxSyncConfig.load();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.AyuSyncScreenTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDurations(350);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onItemClick(view, position));

        updateItems(false);
        return fragmentView;
    }

    // ---------------------------------------------------------------- clicks

    private void onItemClick(View view, int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        final ItemInner item = items.get(position);
        if (item.id == ID_ORDER) {
            orderService();
        } else if (item.id == ID_API_ID) {
            showEditDialog(
                    LocaleController.getString(R.string.OxSyncApiId),
                    OxSyncConfig.getApiId() > 0 ? String.valueOf(OxSyncConfig.getApiId()) : "",
                    "1234567",
                    InputType.TYPE_CLASS_NUMBER,
                    value -> {
                        if (TextUtils.isEmpty(value)) {
                            OxSyncConfig.setApiId(0);
                            updateItems(true);
                            return;
                        }
                        long parsed;
                        try {
                            parsed = Long.parseLong(value.replaceAll("[^0-9]", ""));
                        } catch (Exception e) {
                            parsed = 0;
                        }
                        if (parsed <= 0) {
                            if (BulletinFactory.canShowBulletin(this)) {
                                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.OxSyncApiIdInvalid)).show();
                            }
                            return;
                        }
                        OxSyncConfig.setApiId(parsed);
                        onCredentialSaved();
                    });
        } else if (item.id == ID_API_HASH) {
            showEditDialog(
                    LocaleController.getString(R.string.OxSyncApiHash),
                    OxSyncConfig.getApiHash(),
                    "0123456789abcdef0123456789abcdef",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    value -> {
                        OxSyncConfig.setApiHash(value);
                        onCredentialSaved();
                    });
        } else if (item.id == ID_SESSION) {
            showEditDialog(
                    LocaleController.getString(R.string.OxSyncSessionString),
                    OxSyncConfig.getSessionString(),
                    LocaleController.getString(R.string.OxSyncSessionHint),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    value -> {
                        OxSyncConfig.setSessionString(value);
                        onCredentialSaved();
                    });
        } else if (item.id == ID_CLEAR) {
            confirmClear();
        }
    }

    private void onCredentialSaved() {
        updateItems(true);
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.OxSyncSaved)).show();
        }
    }

    private AlertDialog progressDialog;

    private void orderService() {
        if (getParentActivity() == null) {
            return;
        }
        if (progressDialog != null) {
            return;
        }
        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.showDelayed(150);
        MessagesController.getInstance(currentAccount).getUserNameResolver().resolve(ORDER_USERNAME, peerId -> {
            if (progressDialog != null) {
                progressDialog.dismiss();
                progressDialog = null;
            }
            if (getParentActivity() == null) {
                return;
            }
            if (peerId == null || peerId == Long.MAX_VALUE || peerId <= 0) {
                openOrderLink();
                return;
            }
            final Bundle args = new Bundle();
            args.putLong("user_id", peerId);
            presentFragment(new ChatActivity(args));
        });
    }

    private void openOrderLink() {
        if (getParentActivity() == null) {
            return;
        }
        Browser.openUrl(getParentActivity(), "https://t.me/" + ORDER_USERNAME);
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.OxSyncClearTitle));
        builder.setMessage(LocaleController.getString(R.string.OxSyncClearText));
        builder.setPositiveButton(LocaleController.getString(R.string.OxSyncClearButton), (dialog, which) -> {
            OxSyncConfig.clear();
            updateItems(true);
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.OxSyncCleared)).show();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        final View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    // ---------------------------------------------------------------- dialog

    private interface OnValueEntered {
        void run(String value);
    }

    private void showEditDialog(String title, String currentValue, String hint, int inputType, OnValueEntered callback) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setBackgroundDrawable(Theme.createEditTextDrawable(context, true));
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setSingleLine(true);
        editText.setHint(hint);
        editText.setInputType(inputType);
        editText.setText(currentValue == null ? "" : currentValue);
        editText.setSelection(editText.getText().length());
        editText.setPadding(0, dp(4), 0, dp(4));

        final FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 24, 6, 24, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String value = editText.getText().toString().trim();
            AndroidUtilities.hideKeyboard(editText);
            callback.run(value);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 80));
        showDialog(dialog);
    }

    // ---------------------------------------------------------------- items

    private void updateItems(boolean animated) {
        oldItems.clear();
        oldItems.addAll(items);
        items.clear();

        items.add(new ItemInner(VIEW_TYPE_HERO, 10, null, null));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 11, null, null));

        items.add(new ItemInner(VIEW_TYPE_HEADER, 12, LocaleController.getString(R.string.OxSyncWhatHeader), null));
        items.add(new ItemInner(VIEW_TYPE_BULLET, 20, LocaleController.getString(R.string.OxSyncBulletDestruct), null));
        items.add(new ItemInner(VIEW_TYPE_BULLET, 21, LocaleController.getString(R.string.OxSyncBulletOnce), null));
        items.add(new ItemInner(VIEW_TYPE_BULLET, 22, LocaleController.getString(R.string.OxSyncBulletDeleted), null));
        items.add(new ItemInner(VIEW_TYPE_BULLET, 23, LocaleController.getString(R.string.OxSyncBulletOffline), null));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 13, LocaleController.getString(R.string.OxSyncWhatInfo), null));

        items.add(new ItemInner(VIEW_TYPE_BUTTON, ID_ORDER, LocaleController.getString(R.string.OxSyncOrder), null));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 14, LocaleController.getString(R.string.OxSyncOrderInfo), null));

        items.add(new ItemInner(VIEW_TYPE_HEADER, 15, LocaleController.getString(R.string.OxSyncCredentialsSection), null));
        items.add(new ItemInner(VIEW_TYPE_SETTINGS, ID_API_ID, LocaleController.getString(R.string.OxSyncApiId), apiIdValue()));
        items.add(new ItemInner(VIEW_TYPE_SETTINGS, ID_API_HASH, LocaleController.getString(R.string.OxSyncApiHash), maskedApiHash()));
        items.add(new ItemInner(VIEW_TYPE_SETTINGS, ID_SESSION, LocaleController.getString(R.string.OxSyncSessionString), maskedSession()));
        items.add(new ItemInner(VIEW_TYPE_SETTINGS, ID_STATUS, LocaleController.getString(R.string.OxSyncStatus), statusValue()));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 16, LocaleController.getString(R.string.OxSyncCredentialsInfo), null));

        items.add(new ItemInner(VIEW_TYPE_BUTTON, ID_CLEAR, LocaleController.getString(R.string.OxSyncClear), null));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 17, null, null));

        if (adapter == null) {
            return;
        }
        if (animated) {
            adapter.setItems(oldItems, items);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private CharSequence apiIdValue() {
        final long id = OxSyncConfig.getApiId();
        if (id <= 0) {
            return LocaleController.getString(R.string.OxSyncNotSet);
        }
        return String.valueOf(id);
    }

    private CharSequence maskedApiHash() {
        return mask(OxSyncConfig.getApiHash());
    }

    private CharSequence maskedSession() {
        return mask(OxSyncConfig.getSessionString());
    }

    private CharSequence mask(String value) {
        if (TextUtils.isEmpty(value)) {
            return LocaleController.getString(R.string.OxSyncNotSet);
        }
        if (value.length() <= 6) {
            return "••••••";
        }
        return value.substring(0, 6) + "…";
    }

    private CharSequence statusValue() {
        return LocaleController.getString(OxSyncConfig.isConfigured()
                ? R.string.OxSyncStatusConfigured
                : R.string.OxSyncStatusNotConfigured);
    }

    private static int bulletIcon(int id) {
        switch (id) {
            case 20:
                return R.drawable.msg_secret;
            case 21:
                return R.drawable.msg_views;
            case 22:
                return R.drawable.msg_delete;
            case 23:
            default:
                return R.drawable.msg_download;
        }
    }

    // ---------------------------------------------------------------- hero

    private static class HeroCell extends LinearLayout {

        public HeroCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(22), dp(20), dp(22), dp(20));

            final ImageView imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            imageView.setImageResource(R.drawable.msg_secret);
            imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon), PorterDuff.Mode.MULTIPLY));
            addView(imageView, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL));

            final TextView titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER_HORIZONTAL);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setText(LocaleController.getString(R.string.OxSyncHeroTitle));
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

            final TextView textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setGravity(Gravity.CENTER_HORIZONTAL);
            textView.setLineSpacing(dp(2), 1f);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            textView.setText(LocaleController.getString(R.string.OxSyncHeroText));
            addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        }
    }

    // ---------------------------------------------------------------- adapter

    private static class ItemInner extends AdapterWithDiffUtils.Item {
        public final int id;
        public final CharSequence text;
        public final CharSequence value;

        public ItemInner(int viewType, int id, CharSequence text, CharSequence value) {
            super(viewType, false);
            this.id = id;
            this.text = text;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemInner item = (ItemInner) o;
            return id == item.id && viewType == item.viewType
                    && Objects.equals(text, item.text)
                    && Objects.equals(value, item.value);
        }
    }

    private class ListAdapter extends AdapterWithDiffUtils {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HERO) {
                view = new HeroCell(getContext());
            } else if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(getContext());
            } else if (viewType == VIEW_TYPE_SETTINGS) {
                view = new TextSettingsCell(getContext());
            } else if (viewType == VIEW_TYPE_BULLET) {
                TextCell cell = new TextCell(getContext());
                cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlackText);
                view = cell;
            } else if (viewType == VIEW_TYPE_BUTTON) {
                view = new TextCell(getContext());
            } else {
                view = new TextInfoPrivacyCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final ItemInner item = items.get(position);
            final boolean divider = position + 1 < items.size() && items.get(position + 1).viewType == item.viewType;
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HERO:
                    break;
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(item.text);
                    break;
                case VIEW_TYPE_SHADOW: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (TextUtils.isEmpty(item.text)) {
                        cell.setFixedSize(12);
                        cell.setText(null);
                    } else {
                        cell.setFixedSize(0);
                        cell.setText(item.text);
                    }
                    break;
                }
                case VIEW_TYPE_BULLET: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlackText);
                    cell.setTextAndIcon(item.text, bulletIcon(item.id), divider);
                    break;
                }
                case VIEW_TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(item.text, item.value, divider);
                    break;
                }
                case VIEW_TYPE_BUTTON: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (item.id == ID_CLEAR) {
                        cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
                        cell.setTextAndIcon(item.text, R.drawable.msg_clear, divider);
                    } else {
                        cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                        cell.setTextAndIcon(item.text, R.drawable.msg_contact_add, divider);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int type = holder.getItemViewType();
            if (type == VIEW_TYPE_SHADOW || type == VIEW_TYPE_HEADER || type == VIEW_TYPE_HERO || type == VIEW_TYPE_BULLET) {
                return false;
            }
            final int position = holder.getAdapterPosition();
            return !(position >= 0 && position < items.size() && items.get(position).id == ID_STATUS);
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return VIEW_TYPE_SHADOW;
            }
            return items.get(position).viewType;
        }
    }
}
