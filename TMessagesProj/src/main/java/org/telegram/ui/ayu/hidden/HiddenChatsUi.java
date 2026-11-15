package org.telegram.ui.ayu.hidden;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.hidden.HiddenChatsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ArticleViewer;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PipVideoOverlay;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SecretMediaViewer;
import org.telegram.ui.Stories.StoryViewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/** Account-scoped password gates. Passwords never enter bundles or saved view state. */
public final class HiddenChatsUi {
    public static final String AUTHORIZED_ARGUMENT = "ox_hidden_authorized";
    private static final HashMap<BaseFragment, Gate> gates = new HashMap<>();

    private HiddenChatsUi() { }

    public static void open(BaseFragment host) {
        final int account = host.getCurrentAccount();
        // Entering the vault always asks for its own credential, including after a hide action.
        HiddenChatsController.getInstance(account).lock();
        authorize(host, () -> {
            HiddenChatsActivity activity = new HiddenChatsActivity(account);
            host.presentFragment(activity);
        }, null);
    }

    public static void authorize(BaseFragment host, Runnable success) {
        authorize(host, success, null);
    }

    public static void authorize(BaseFragment host, Runnable success, Runnable cancel) {
        if (!valid(host, host.getCurrentAccount())) {
            if (cancel != null) cancel.run();
            return;
        }
        HiddenChatsController controller = HiddenChatsController.getInstance(host.getCurrentAccount());
        if (controller.isUnlocked()) {
            success.run();
        } else if (!gates.containsKey(host)) {
            new Gate(host, controller.isConfigured() ? Mode.VERIFY : Mode.SETUP, success, cancel).show();
        }
    }

    public static void cancelAuthorization(BaseFragment host) {
        Gate gate = gates.get(host);
        if (gate != null) gate.cancel();
    }

    public static void cancelAllAuthorizations() {
        for (Gate gate : new ArrayList<>(gates.values())) gate.cancel();
    }

    /** Close private child screens too: profiles and shared media must not survive a background lock. */
    public static boolean closeProtectedNavigation(INavigationLayout layout) {
        if (layout == null) return false;
        ArrayList<BaseFragment> stack = new ArrayList<>(layout.getFragmentStack());
        int firstProtected = -1;
        for (int i = 0; i < stack.size(); i++) {
            BaseFragment fragment = stack.get(i);
            long dialogId = fragment instanceof ChatActivity ? ((ChatActivity) fragment).getDialogId()
                    : fragment instanceof ProfileActivity ? ((ProfileActivity) fragment).getDialogId() : 0;
            if (fragment instanceof HiddenChatsActivity || dialogId != 0
                    && HiddenChatsController.getInstance(fragment.getCurrentAccount()).isHidden(dialogId)) {
                firstProtected = i;
                break;
            }
        }
        if (firstProtected < 0) return false;
        // Conceal the entire suffix before removal can resume any intermediate screen.
        for (int i = firstProtected; i < stack.size(); i++) {
            View view = stack.get(i).getFragmentView();
            if (view != null) view.setVisibility(View.INVISIBLE);
        }
        // These viewers own separate windows and would otherwise outlive the removed fragments.
        PhotoViewer pipViewer = PhotoViewer.getPipInstance();
        if (PipVideoOverlay.isVisible()) PipVideoOverlay.dismiss(true, true);
        if (pipViewer != null) pipViewer.destroyPhotoViewer();
        if (PhotoViewer.hasInstance() && (PhotoViewer.getInstance().isVisible() || PhotoViewer.getInstance().isVisibleOrAnimating())) {
            PhotoViewer viewer = PhotoViewer.getInstance();
            viewer.closePhoto(false, false);
            if (viewer.isVisible() || viewer.isVisibleOrAnimating()) viewer.destroyPhotoViewer();
        }
        if (SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible()) {
            SecretMediaViewer viewer = SecretMediaViewer.getInstance();
            viewer.closePhoto(false, false);
            if (viewer.isVisible()) viewer.destroyPhotoViewer();
        }
        if (ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible()) {
            ArticleViewer viewer = ArticleViewer.getInstance();
            viewer.close(false, true);
            if (viewer.isVisible()) viewer.destroyArticleViewer();
        }
        for (int i = firstProtected; i < stack.size(); i++) {
            StoryViewer story = stack.get(i).getLastStoryViewer();
            if (story != null && story.isShown()) {
                if (story.getWindowView() != null) story.getWindowView().setVisibility(View.INVISIBLE);
                story.close(false);
            }
        }
        for (int i = stack.size() - 1; i >= firstProtected; i--) {
            BaseFragment fragment = stack.get(i);
            cancelAuthorization(fragment);
            if (layout.getFragmentStack().contains(fragment)) layout.removeFragmentFromStack(fragment, true);
        }
        if (!layout.getFragmentStack().isEmpty()) layout.showLastFragment();
        return true;
    }

    public static void hideChat(BaseFragment host, long dialogId) {
        hideChat(host, dialogId, null);
    }

    public static void hideChat(BaseFragment host, long dialogId, Runnable onHidden) {
        final int account = host.getCurrentAccount();
        final boolean temporarySession = !HiddenChatsController.getInstance(account).isUnlocked();
        authorize(host, () -> confirmVisibility(host, account, dialogId, true, temporarySession, onHidden), null);
    }

    public static void unhideChat(BaseFragment host, long dialogId, Runnable onUnhidden) {
        final int account = host.getCurrentAccount();
        final boolean temporarySession = !HiddenChatsController.getInstance(account).isUnlocked();
        authorize(host, () -> confirmVisibility(host, account, dialogId, false, temporarySession, onUnhidden), null);
    }

    private static void confirmVisibility(BaseFragment host, int account, long id, boolean hide, boolean temporarySession, Runnable done) {
        if (!valid(host, account)) {
            if (temporarySession) HiddenChatsController.getInstance(account).lock();
            return;
        }
        if (host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(getString(hide ? R.string.OxHiddenHideChat : R.string.OxHiddenUnhideChat))
                .setMessage(getString(hide ? R.string.OxHiddenHideInfo : R.string.OxHiddenUnhideInfo))
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(hide ? R.string.OxHiddenHideChat : R.string.OxHiddenUnhideChat), (dialog, which) -> {
                    if (!valid(host, account)) return;
                    HiddenChatsController controller = HiddenChatsController.getInstance(account);
                    if (!controller.isUnlocked()) {
                        authorize(host, () -> confirmVisibility(host, account, id, hide, temporarySession, done), null);
                    } else if (controller.setHidden(id, hide)) {
                        if (done != null) done.run();
                    } else {
                        error(host, R.string.OxHiddenStorageError);
                    }
                }).create(), ignored -> {
                    if (temporarySession) HiddenChatsController.getInstance(account).lock();
                }) == null && temporarySession) HiddenChatsController.getInstance(account).lock();
    }

    static void changePassword(BaseFragment host) {
        if (!gates.containsKey(host)) new Gate(host, Mode.CHANGE, () -> { }, null).show();
    }

    static void setBiometric(BaseFragment host, boolean enabled, Runnable done) {
        final int account = host.getCurrentAccount();
        if (enabled && !biometricAvailable(host.getParentActivity())) {
            error(host, R.string.OxHiddenBiometricUnavailable);
            return;
        }
        if (!gates.containsKey(host)) new Gate(host, Mode.PASSWORD, () -> {
            if (valid(host, account) && HiddenChatsController.getInstance(account).setBiometricEnabled(enabled)) done.run();
            else error(host, R.string.OxHiddenStorageError);
        }, null).show();
    }

    static void disable(BaseFragment host, Runnable done) {
        final int account = host.getCurrentAccount();
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(getString(R.string.OxHiddenDisable))
                .setMessage(getString(R.string.OxHiddenDisableInfo))
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.Continue), (dialog, which) -> {
                    if (!valid(host, account) || gates.containsKey(host)) return;
                    new Gate(host, Mode.PASSWORD, () -> {
                        if (valid(host, account) && HiddenChatsController.getInstance(account).disableAndUnhideAll()) done.run();
                        else error(host, R.string.OxHiddenStorageError);
                    }, null).show();
                }).create());
    }

    private static boolean valid(BaseFragment host, int account) {
        return !host.isFinished && host.getParentActivity() != null && !host.getParentActivity().isFinishing()
                && host.getCurrentAccount() == account && UserConfig.selectedAccount == account
                && !ApplicationLoader.mainInterfacePaused;
    }

    private static boolean biometricAvailable(Context context) {
        try {
            return context instanceof LaunchActivity && BiometricManager.from(context).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void error(BaseFragment host, int message) {
        if (host.getParentActivity() != null && !host.isFinished) {
            host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                    .setTitle(getString(R.string.OxHiddenTitle)).setMessage(getString(message))
                    .setPositiveButton(getString(R.string.OK), null).create());
        }
    }

    private enum Mode { SETUP, VERIFY, PASSWORD, CHANGE }

    private static final class Gate {
        final BaseFragment host;
        final int account;
        final HiddenChatsController controller;
        final Mode mode;
        final Runnable success;
        final Runnable cancel;
        final ArrayList<EditTextBoldCursor> inputs = new ArrayList<>();
        AlertDialog dialog;
        TextView status;
        FlagSecureReason secure;
        BiometricPrompt biometric;
        boolean closed;
        boolean completed;
        boolean busy;
        int biometricAttempt;
        int showAttempts;
        final Runnable retryShow = this::show;

        Gate(BaseFragment host, Mode mode, Runnable success, Runnable cancel) {
            this.host = host;
            this.account = host.getCurrentAccount();
            this.controller = HiddenChatsController.getInstance(account);
            this.mode = mode;
            this.success = success;
            this.cancel = cancel;
        }

        void show() {
            if (closed) return;
            if (!valid(host, account)) {
                dismiss();
                return;
            }
            gates.put(host, this);
            if (host.getParentLayout() != null && (host.getParentLayout().isTransitionAnimationInProgress()
                    || host.getParentLayout().isSwipeInProgress())) {
                if (++showAttempts < 20) AndroidUtilities.runOnUIThread(retryShow, 100);
                else dismiss();
                return;
            }
            Context context = host.getParentActivity();
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(8));
            TextView explanation = new TextView(context);
            explanation.setText(getString(mode == Mode.SETUP ? R.string.OxHiddenSetupInfo :
                    mode == Mode.CHANGE ? R.string.OxHiddenChangeInfo : R.string.OxHiddenPasswordInfo));
            explanation.setTextSize(14);
            explanation.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
            content.addView(explanation, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
            if (mode == Mode.CHANGE) addInput(content, R.string.OxHiddenOldPassword);
            addInput(content, mode == Mode.SETUP || mode == Mode.CHANGE ? R.string.OxHiddenNewPassword : R.string.OxHiddenPassword);
            if (mode == Mode.SETUP || mode == Mode.CHANGE) addInput(content, R.string.OxHiddenConfirmPassword);
            status = new TextView(context);
            status.setTextColor(Theme.getColor(Theme.key_text_RedRegular, host.getResourceProvider()));
            status.setTextSize(14);
            content.addView(status, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
            AlertDialog.Builder builder = new AlertDialog.Builder(context, host.getResourceProvider())
                    .setTitle(getString(mode == Mode.SETUP ? R.string.OxHiddenSetup : mode == Mode.CHANGE ? R.string.OxHiddenChangePassword : R.string.OxHiddenUnlock))
                    .setView(content).setNegativeButton(getString(R.string.Cancel), null)
                    .setPositiveButton(getString(mode == Mode.SETUP || mode == Mode.CHANGE ? R.string.Save : R.string.OxHiddenUnlock), null);
            boolean allowBiometric = mode == Mode.VERIFY && controller.isBiometricEnabled() && biometricAvailable(context);
            if (allowBiometric) builder.setNeutralButton(getString(R.string.OxHiddenBiometric), null);
            dialog = builder.create();
            secure = new FlagSecureReason(host.getParentActivity().getWindow(), () -> true, true);
            secure.attach();
            if (host.showDialog(dialog, true, ignored -> dismiss()) == null) {
                dismiss();
                return;
            }
            dialog.setCanceledOnTouchOutside(false);
            if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit());
            if (allowBiometric) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> startBiometric());
            inputs.get(0).requestFocus();
            AndroidUtilities.showKeyboard(inputs.get(0));
        }

        void addInput(LinearLayout parent, int hint) {
            EditTextBoldCursor input = new EditTextBoldCursor(parent.getContext());
            input.setTextSize(18);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            input.setSaveEnabled(false);
            if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
            input.setHint(getString(hint));
            input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
            input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, host.getResourceProvider()));
            input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            parent.addView(input, LayoutHelper.createLinear(-1, 48));
            inputs.add(input);
        }

        char[] read(int index) {
            android.text.Editable value = inputs.get(index).getText();
            char[] copy = new char[value.length()];
            value.getChars(0, value.length(), copy, 0);
            return copy;
        }

        void submit() {
            if (closed || busy || !valid(host, account)) return;
            biometricAttempt++;
            if (biometric != null) {
                biometric.cancelAuthentication();
                biometric = null;
            }
            char[] first = read(0);
            char[] next = mode == Mode.CHANGE ? read(1) : first;
            char[] confirmation = mode == Mode.SETUP ? read(1) : mode == Mode.CHANGE ? read(2) : null;
            try {
                if (confirmation != null && !Arrays.equals(next, confirmation)) {
                    status.setText(getString(R.string.OxHiddenPasswordMismatch));
                    return;
                }
                if (confirmation != null && (next.length < 4 || next.length > 256)) {
                    status.setText(getString(R.string.OxHiddenPasswordLength));
                    return;
                }
                busy = true;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                status.setText(getString(R.string.OxHiddenChecking));
                if (mode == Mode.SETUP) controller.setPasscode(next, this::onResult);
                else if (mode == Mode.CHANGE) controller.changePasscode(first, next, this::onResult);
                else controller.verifyPasscode(first, this::onResult);
            } finally {
                Arrays.fill(first, '\0');
                Arrays.fill(next, '\0');
                if (confirmation != null) Arrays.fill(confirmation, '\0');
            }
        }

        void onResult(HiddenChatsController.PasscodeResult result) {
            if (closed) return;
            if (!valid(host, account)) {
                controller.lock();
                cancel();
                return;
            }
            busy = false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            if (result == HiddenChatsController.PasscodeResult.SUCCESS) {
                complete();
                return;
            }
            int message;
            switch (result) {
                case INVALID_PASSCODE: message = R.string.OxHiddenWrongPassword; break;
                case THROTTLED: message = R.string.OxHiddenTryLater; break;
                case INVALID_NEW_PASSCODE: message = R.string.OxHiddenPasswordLength; break;
                case CORRUPT_STATE: message = R.string.OxHiddenCorrupt; break;
                case STALE_SESSION: message = R.string.OxHiddenSessionExpired; break;
                default: message = R.string.OxHiddenStorageError;
            }
            status.setText(getString(message));
            if (mode == Mode.VERIFY || mode == Mode.PASSWORD || mode == Mode.CHANGE) inputs.get(0).setText("");
        }

        void startBiometric() {
            if (closed || busy || biometric != null || !valid(host, account)) return;
            AndroidUtilities.hideKeyboard(inputs.get(0));
            final int attempt = ++biometricAttempt;
            try {
                biometric = new BiometricPrompt((LaunchActivity) host.getParentActivity(),
                        ContextCompat.getMainExecutor(host.getParentActivity()), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        if (closed || attempt != biometricAttempt || !valid(host, account) || !controller.isBiometricEnabled()) return;
                        controller.unlock();
                        if (controller.isUnlocked()) complete();
                        else {
                            biometric = null;
                            status.setText(getString(R.string.OxHiddenTryLater));
                        }
                    }
                    @Override public void onAuthenticationError(int code, @NonNull CharSequence message) {
                        if (!closed && attempt == biometricAttempt) {
                            biometric = null;
                            status.setText(getString(R.string.OxHiddenPasswordFallback));
                            inputs.get(0).requestFocus();
                            AndroidUtilities.showKeyboard(inputs.get(0));
                        }
                    }
                    @Override public void onAuthenticationFailed() {
                        if (!closed && attempt == biometricAttempt) status.setText(getString(R.string.OxHiddenPasswordFallback));
                    }
                });
                biometric.authenticate(new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(getString(R.string.OxHiddenUnlock))
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setNegativeButtonText(getString(R.string.OxHiddenUsePassword)).build());
            } catch (Exception ignored) {
                biometric = null;
                status.setText(getString(R.string.OxHiddenPasswordFallback));
            }
        }

        void complete() {
            completed = true;
            dialog.dismiss();
            if (valid(host, account)) success.run();
        }

        void cancel() {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            else dismiss();
        }

        void dismiss() {
            if (closed) return;
            closed = true;
            AndroidUtilities.cancelRunOnUIThread(retryShow);
            if (!completed && (busy || mode == Mode.SETUP || mode == Mode.VERIFY)) controller.lock();
            biometricAttempt++;
            if (gates.get(host) == this) gates.remove(host);
            if (biometric != null) biometric.cancelAuthentication();
            for (EditTextBoldCursor input : inputs) {
                AndroidUtilities.hideKeyboard(input);
                input.setText("");
            }
            if (secure != null) secure.detach();
            if (!completed && cancel != null) cancel.run();
        }
    }
}
