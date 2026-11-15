package org.telegram.ui.ayu;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.messenger.ayu.hidden.HiddenChatsController;
import org.telegram.messenger.ayu.entities.DeletedMessage;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;
import java.io.File;

/** Account-bound browser for complete, locally retained timed photos and videos. */
public class SavedTimedPhotosActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int PHOTO = 1;
    private static final int MORE = 2;
    private static final int RETRY = 3;
    private static final int REFRESH = 4;
    private static final int PAGE_SIZE = 40;

    private final int sourceAccount;
    private final long ownerId;
    private final ArrayList<PhotoRow> photos = new ArrayList<>();
    private boolean loading;
    private boolean deleting;
    private boolean failed;
    private boolean hasMore = true;
    private boolean destroyed;
    private int generation;
    private int openRequest;
    private long beforeId;
    private PhotoRow openedPhoto;

    private static final class PhotoRow {
        final DeletedMessage media;
        final String title;
        final String date;
        final boolean video;

        PhotoRow(int account, DeletedMessage media) {
            this.media = media;
            video = media.documentType == AyuConstants.DOCUMENT_TYPE_VIDEO || media.documentType == AyuConstants.DOCUMENT_TYPE_ROUND;
            String name = null;
            MessagesController controller = MessagesController.getInstance(account);
            if (media.dialogId > 0) {
                TLRPC.User user = controller.getUser(media.dialogId);
                if (user != null) name = UserObject.getUserName(user);
            } else if (media.dialogId < 0) {
                TLRPC.Chat chat = controller.getChat(-media.dialogId);
                if (chat != null) name = chat.title;
            }
            title = name == null || name.length() == 0 ? getString(video ? R.string.OxTimedVideo : R.string.OxTimedPhoto) : name;
            date = LocaleController.formatDateTime(media.date, true);
        }
    }

    public SavedTimedPhotosActivity(int account) {
        super();
        setCurrentAccount(account);
        sourceAccount = account;
        ownerId = UserConfig.getInstance(account).getClientUserId();
    }

    private boolean isCurrentOwner() {
        return !destroyed && !isFinished && ownerId != 0 && currentAccount == sourceAccount
                && UserConfig.selectedAccount == sourceAccount
                && UserConfig.getInstance(sourceAccount).isClientActivated()
                && UserConfig.getInstance(sourceAccount).getClientUserId() == ownerId;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!isCurrentOwner() || !super.onFragmentCreate()) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuConfigChanged);
        return true;
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        loadPage(true);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        removeHiddenRows();
        if (!isCurrentOwner()) {
            photos.clear();
            updateList();
            finishFragment();
        }
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuConfigChanged);
        destroyed = true;
        generation++;
        photos.clear();
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ayuConfigChanged) removeHiddenRows();
    }

    private boolean visible(PhotoRow photo) {
        return !HiddenChatsController.getInstance(sourceAccount).isHidden(photo.media.dialogId);
    }

    private void removeHiddenRows() {
        if (openedPhoto != null && (!isCurrentOwner() || !visible(openedPhoto))) {
            // This screen never opens hidden sources, even during an unlocked hidden-chat session.
            if (PhotoViewer.hasInstance()) PhotoViewer.getInstance().closePhoto(false, false);
            PhotoViewer pip = PhotoViewer.getPipInstance();
            if (pip != null) pip.destroyPhotoViewer();
            openedPhoto = null;
            openRequest++;
        }
        if (photos.removeIf(photo -> !visible(photo))) updateList();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.OxSavedTimedPhotos);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(getString(R.string.OxSavedTimedPhotosInfo)));
        for (PhotoRow photo : photos) {
            if (!visible(photo)) continue;
            UItem item = UItem.asButton(PHOTO, photo.video ? R.drawable.msg_video : R.drawable.msg_photos, photo.title, photo.date);
            item.object = photo;
            items.add(item);
        }
        if (loading || deleting) {
            items.add(UItem.asShadow(getString(loading ? R.string.Loading : R.string.OxTimedPhotosRemoving)));
        } else if (failed) {
            items.add(UItem.asShadow(getString(R.string.OxTimedPhotosLoadError)));
            items.add(UItem.asButton(RETRY, getString(R.string.OxTimedPhotosRetry)));
        } else {
            if (photos.isEmpty()) items.add(UItem.asShadow(getString(R.string.OxTimedPhotosEmpty)));
            if (hasMore) items.add(UItem.asButton(MORE, getString(R.string.OxTimedPhotosLoadMore)));
            items.add(UItem.asButton(REFRESH, getString(R.string.OxTimedPhotosRefresh)));
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    private void loadPage(boolean reset) {
        if (!isCurrentOwner() || loading || deleting) return;
        if (reset) {
            beforeId = 0;
            hasMore = true;
            photos.clear();
        }
        if (!hasMore) return;
        loading = true;
        failed = false;
        final int request = ++generation;
        updateList();
        AyuMessagesController.getInstance().getSavedTTLMediaAsync(sourceAccount, beforeId, PAGE_SIZE, result -> {
            if (!isCurrentOwner() || request != generation) return;
            loading = false;
            if (result == null) {
                failed = true;
            } else {
                for (DeletedMessage media : result) {
                    if (media.userId == ownerId && media.mediaPath != null && !media.mediaPath.isEmpty()
                            && !HiddenChatsController.getInstance(sourceAccount).isHidden(media.dialogId)) {
                        photos.add(new PhotoRow(sourceAccount, media));
                    }
                }
                hasMore = result.size() == PAGE_SIZE;
                if (!result.isEmpty()) beforeId = result.get(result.size() - 1).fakeId;
            }
            updateList();
        });
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!isCurrentOwner() || deleting) return;
        if (item.id == PHOTO && item.object instanceof PhotoRow) {
            openPhoto((PhotoRow) item.object);
        } else if (item.id == MORE || item.id == RETRY) {
            loadPage(false);
        } else if (item.id == REFRESH) {
            loadPage(true);
        }
    }

    private void openPhoto(PhotoRow photo) {
        if (!isCurrentOwner() || getParentActivity() == null || !photos.contains(photo) || !visible(photo)) return;
        final int request = ++openRequest;
        final int listGeneration = generation;
        final String path = photo.media.mediaPath;
        Utilities.globalQueue.postRunnable(() -> {
            boolean valid;
            try {
                File file = new File(path);
                valid = file.isFile() && file.length() > 0;
            } catch (RuntimeException e) {
                valid = false;
            }
            final boolean exists = valid;
            AndroidUtilities.runOnUIThread(() -> {
                if (!isCurrentOwner() || request != openRequest || listGeneration != generation
                        || getParentActivity() == null || !photos.contains(photo) || !visible(photo)) return;
                if (exists) showPhoto(photo);
                else showError(getString(R.string.OxTimedPhotosOpenError));
            });
        });
    }

    private void showPhoto(PhotoRow photo) {
        ArrayList<Object> entries = new ArrayList<>();
        entries.add(new MediaController.PhotoEntry(0, 0, 0, photo.media.mediaPath, 0, photo.video, 0, 0, 0));
        openedPhoto = photo;
        PhotoViewer viewer = PhotoViewer.getInstance();
        viewer.setParentActivity(this);
        if (!viewer.openPhotoForSelect(entries, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false,
                new PhotoViewer.EmptyPhotoViewerProvider(), null)) {
            showError(getString(R.string.OxTimedPhotosOpenError));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id != PHOTO || !(item.object instanceof PhotoRow) || !isCurrentOwner() || loading || deleting) return false;
        PhotoRow photo = (PhotoRow) item.object;
        if (getParentActivity() == null || !visible(photo)) return false;
        showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.OxTimedPhotosRemove))
                .setMessage(getString(R.string.OxTimedPhotosRemoveInfo))
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> removePhoto(photo))
                .create());
        return true;
    }

    private void removePhoto(PhotoRow photo) {
        if (!isCurrentOwner() || loading || deleting || !photos.contains(photo) || !visible(photo)) return;
        deleting = true;
        final int request = ++generation;
        updateList();
        AyuMessagesController.getInstance().removeSavedTTLMediaAsync(sourceAccount, photo.media.fakeId, success -> {
            if (!isCurrentOwner() || request != generation) return;
            deleting = false;
            if (Boolean.TRUE.equals(success)) photos.remove(photo);
            else showError(getString(R.string.OxTimedPhotosRemoveError));
            updateList();
        });
    }

    private void showError(CharSequence text) {
        if (isCurrentOwner() && getParentActivity() != null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, text).show();
        }
    }
}
