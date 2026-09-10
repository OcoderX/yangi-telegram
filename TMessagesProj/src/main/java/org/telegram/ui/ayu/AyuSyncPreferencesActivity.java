package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.messenger.ayu.sync.AyuSyncController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Objects;

/**
 * AyuGram: settings screen of the AyuSync client
 * ({@link org.telegram.messenger.ayu.sync.AyuSyncController}).
 */
public class AyuSyncPreferencesActivity extends BaseFragment implements AyuSyncController.AyuSyncListener {

    private static final int ID_ENABLE = 1;
    private static final int ID_SECURE = 2;
    private static final int ID_URL = 3;
    private static final int ID_TOKEN = 4;
    private static final int ID_STATUS = 5;
    private static final int ID_FORCE = 6;
    private static final int ID_REGISTER = 7;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHECK = 1;
    private static final int VIEW_TYPE_SHADOW = 2;
    private static final int VIEW_TYPE_SETTINGS = 3;
    private static final int VIEW_TYPE_BUTTON = 4;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<ItemInner> oldItems = new ArrayList<>(), items = new ArrayList<>();

    public AyuSyncPreferencesActivity() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        AyuSyncController.getInstance().addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AyuSyncController.getInstance().removeListener(this);
        super.onFragmentDestroy();
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

    private void onItemClick(View view, int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        final ItemInner item = items.get(position);
        if (item.id == ID_ENABLE) {
            AyuConfig.setSyncEnabled(!AyuConfig.syncEnabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(AyuConfig.syncEnabled);
            }
            AyuSyncController.getInstance().checkState();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
            updateItems(true);
        } else if (item.id == ID_SECURE) {
            AyuConfig.setUseSecureConnection(!AyuConfig.useSecureConnection);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(AyuConfig.useSecureConnection);
            }
            restartIfRunning();
        } else if (item.id == ID_URL) {
            showEditDialog(LocaleController.getString(R.string.AyuSyncServerUrl), AyuConfig.syncServerURL, AyuConstants.DEFAULT_AYUSYNC_SERVER, false, value -> {
                AyuConfig.setSyncServerURL(TextUtils.isEmpty(value) ? AyuConstants.DEFAULT_AYUSYNC_SERVER : value);
                restartIfRunning();
                updateItems(true);
            });
        } else if (item.id == ID_TOKEN) {
            showEditDialog(LocaleController.getString(R.string.AyuSyncToken), AyuConfig.syncServerToken, "", true, value -> {
                AyuConfig.setSyncServerToken(value == null ? "" : value);
                restartIfRunning();
                updateItems(true);
            });
        } else if (item.id == ID_FORCE) {
            if (!AyuConfig.syncEnabled) {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.AyuSyncNotEnabled)).show();
                return;
            }
            AyuSyncController.getInstance().forceSync();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.AyuSyncForceSyncStarted)).show();
        } else if (item.id == ID_REGISTER) {
            registerDevice();
        }
    }

    private void restartIfRunning() {
        final AyuSyncController controller = AyuSyncController.getInstance();
        controller.stop();
        controller.checkState();
    }

    private AlertDialog progressDialog;

    private void registerDevice() {
        if (getParentActivity() == null) {
            return;
        }
        if (progressDialog != null) {
            return;
        }
        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.showDelayed(150);
        AyuSyncController.getInstance().registerDevice((success, tokenOrError) -> {
            if (progressDialog != null) {
                progressDialog.dismiss();
                progressDialog = null;
            }
            if (!BulletinFactory.canShowBulletin(this)) {
                return;
            }
            if (success) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.AyuSyncRegisterSuccess)).show();
            } else {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString(R.string.AyuSyncRegisterFailed, tokenOrError == null ? "" : tokenOrError)).show();
            }
            updateItems(true);
        });
    }

    private interface OnValueEntered {
        void run(String value);
    }

    private void showEditDialog(String title, String currentValue, String hint, boolean password, OnValueEntered callback) {
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
        editText.setInputType(InputType.TYPE_CLASS_TEXT | (password
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_URI));
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

        items.add(new ItemInner(VIEW_TYPE_HEADER, 0, LocaleController.getString(R.string.AyuSyncScreenTitle), null));
        items.add(new ItemInner(VIEW_TYPE_CHECK, ID_ENABLE, LocaleController.getString(R.string.AyuSyncEnable), null));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 100, LocaleController.getString(R.string.AyuSyncEnableInfo), null));

        items.add(new ItemInner(VIEW_TYPE_HEADER, 101, LocaleController.getString(R.string.AyuSyncServerSection), null));
        items.add(new ItemInner(VIEW_TYPE_CHECK, ID_SECURE, LocaleController.getString(R.string.AyuSyncSecureConnection), null));
        items.add(new ItemInner(VIEW_TYPE_SETTINGS, ID_URL, LocaleController.getString(R.string.AyuSyncServerUrl), serverUrlValue()));
        items.add(new ItemInner(VIEW_TYPE_SETTINGS, ID_TOKEN, LocaleController.getString(R.string.AyuSyncToken), maskedToken()));
        items.add(new ItemInner(VIEW_TYPE_SETTINGS, ID_STATUS, LocaleController.getString(R.string.AyuSyncStatus), statusValue()));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 102, LocaleController.getString(R.string.AyuSyncServerInfo), null));

        items.add(new ItemInner(VIEW_TYPE_BUTTON, ID_FORCE, LocaleController.getString(R.string.AyuSyncForceSync), null));
        items.add(new ItemInner(VIEW_TYPE_BUTTON, ID_REGISTER, LocaleController.getString(R.string.AyuSyncRegisterDevice), null));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, 103, LocaleController.getString(R.string.AyuSyncWhatIsSyncedInfo), null));

        if (adapter == null) {
            return;
        }
        if (animated) {
            adapter.setItems(oldItems, items);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private CharSequence serverUrlValue() {
        final String normalized = AyuSyncController.normalizedBaseUrl();
        if (TextUtils.isEmpty(normalized)) {
            return LocaleController.getString(R.string.AyuSyncNotSet);
        }
        return normalized;
    }

    private CharSequence maskedToken() {
        final String token = AyuConfig.syncServerToken;
        if (TextUtils.isEmpty(token)) {
            return LocaleController.getString(R.string.AyuSyncNotSet);
        }
        if (token.length() <= 4) {
            return "••••";
        }
        return token.substring(0, 4) + "••••••••";
    }

    private CharSequence statusValue() {
        final AyuSyncController controller = AyuSyncController.getInstance();
        switch (controller.getStatus()) {
            case CONNECTING:
                return LocaleController.getString(R.string.AyuSyncStatusConnecting);
            case CONNECTED:
                return LocaleController.getString(R.string.AyuSyncStatusConnected);
            case ERROR:
                final String error = controller.getLastError();
                if (TextUtils.isEmpty(error)) {
                    return LocaleController.getString(R.string.AyuSyncStatusError);
                }
                return LocaleController.getString(R.string.AyuSyncStatusError) + ": " + error;
            case DISABLED:
            default:
                return LocaleController.getString(R.string.AyuSyncStatusDisabled);
        }
    }

    @Override
    public void onAyuSyncStatusChanged(AyuSyncController.Status status, String lastError) {
        AndroidUtilities.runOnUIThread(() -> {
            if (listView == null || adapter == null) {
                return;
            }
            updateItems(false);
        });
    }

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
            if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(getContext());
            } else if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(getContext());
            } else if (viewType == VIEW_TYPE_SETTINGS) {
                view = new TextSettingsCell(getContext());
            } else if (viewType == VIEW_TYPE_BUTTON) {
                TextCell cell = new TextCell(getContext());
                cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                view = cell;
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
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setCheckBoxIcon(0);
                    if (item.id == ID_ENABLE) {
                        cell.setTextAndCheck(item.text, AyuConfig.syncEnabled, divider);
                    } else if (item.id == ID_SECURE) {
                        cell.setTextAndCheck(item.text, AyuConfig.useSecureConnection, divider);
                    }
                    break;
                }
                case VIEW_TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(item.text, item.value, divider);
                    break;
                }
                case VIEW_TYPE_BUTTON: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (item.id == ID_FORCE) {
                        cell.setTextAndIcon(item.text, R.drawable.msg_retry, divider);
                    } else {
                        cell.setTextAndIcon(item.text, R.drawable.msg_link, divider);
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
            if (type == VIEW_TYPE_SHADOW || type == VIEW_TYPE_HEADER) {
                return false;
            }
            final int position = holder.getAdapterPosition();
            if (position >= 0 && position < items.size() && items.get(position).id == ID_STATUS) {
                return false;
            }
            return true;
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
