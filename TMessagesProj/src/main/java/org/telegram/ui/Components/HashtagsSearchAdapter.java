package org.telegram.ui.Components;

import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.ayu.hidden.HiddenChatsController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.MessagesSearchAdapter;
import org.telegram.ui.Stories.StoriesController;

import java.util.ArrayList;

public class HashtagsSearchAdapter extends UniversalAdapter implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    public boolean hasList;
    public StoriesController.SearchStoriesList list;

    public HashtagsSearchAdapter(RecyclerListView listView, Context context, int currentAccount, int folderId, Theme.ResourcesProvider resourcesProvider) {
        super(listView, context, currentAccount, 0, null, resourcesProvider);
        super.fillItems = this::fillItems;
        this.currentAccount = currentAccount;
    }

    private boolean hadStories;
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        messages.removeIf(message -> HiddenChatsController.getInstance(currentAccount).isHidden(message.getDialogId()));
        if (list != null) list.removeHiddenResults();
        boolean hasStories = hasList && list != null && list.getLoadedCount() > 0;
        if (hasStories) {
            items.add(MessagesSearchAdapter.StoriesView.Factory.asStoriesList(list));
        }
        hadStories = hasStories;
        for (int i = 0; i < messages.size(); ++i) {
            items.add(UItem.asSearchMessage(1 + i, messages.get(i)));
        }
        if (!loading && !endReached && messages.isEmpty() && hiddenEmptyPages > 4) {
            items.add(UItem.asGraySection("", LocaleController.getString(R.string.Retry), view -> {
                hiddenEmptyPages = 0;
                search(lastQuery);
            }));
        } else if (loading || !endReached) {
            items.add(UItem.asFlicker(-2, FlickerLoadingView.DIALOG_TYPE));
            items.add(UItem.asFlicker(-3, FlickerLoadingView.DIALOG_TYPE));
            items.add(UItem.asFlicker(-4, FlickerLoadingView.DIALOG_TYPE));
        }
        if (!hadStories && hasStories) {
            AndroidUtilities.runOnUIThread(() -> {
                scrollToTop(true);
            });
        }
    }

    protected boolean loading;
    private int searchId = 0;
    private int reqId = -1;
    private boolean endReached;
    private int totalCount;
    private String lastQuery;
    private String hashtagQuery;
    private int lastRate;
    private TLRPC.Message lastRawMessage;
    private int rawLoadedCount;
    private int hiddenEmptyPages;
    private Runnable searchRunnable;

    public void setInitialData(String hashtag, ArrayList<MessageObject> messages, int messagesLastRate, int totalCount) {
        if (TextUtils.equals(hashtag, hashtagQuery)) return;
        cancel();
        this.messages.clear();
        this.messages.addAll(messages);
        this.totalCount = totalCount;
        rawLoadedCount = messages.size();
        lastRawMessage = messages.isEmpty() ? null : messages.get(messages.size() - 1).messageOwner;
        endReached = rawLoadedCount >= totalCount;
        hiddenEmptyPages = 0;
        if (HiddenChatsController.getInstance(currentAccount).hasHiddenChats()) {
            // Seed results may already be filtered; restart to obtain a trustworthy raw cursor.
            this.messages.clear();
            rawLoadedCount = 0;
            lastRawMessage = null;
            endReached = false;
        }
        this.lastRate = lastRawMessage == null ? 0 : messagesLastRate;
        hashtagQuery = hashtag;
        update(true);
    }

    private final boolean[] cashtag = new boolean[1];

    public void search(String query) {
        lastQuery = query;
        final String hashtag = getHashtag(query, cashtag);
        if (!TextUtils.equals(this.hashtagQuery, hashtag)) {
            messages.clear();
            endReached = false;
            totalCount = 0;
            rawLoadedCount = 0;
            lastRawMessage = null;
            lastRate = 0;
            hiddenEmptyPages = 0;
            cancel();
        } else if (loading) {
            return;
        }
        final int id = ++searchId;
        if (hashtag == null) return;
        loading = true;
        update(true);
        AndroidUtilities.runOnUIThread(searchRunnable = () -> {
            if (id != searchId) {
                return;
            }
            final String finalQuery = (cashtag[0] ? "$" : "#") + hashtag;
            if (list == null || !TextUtils.equals(list.query, finalQuery)) {
                list = new StoriesController.SearchStoriesList(currentAccount, null, finalQuery);
            }
            if (list.getLoadedCount() <= 0) {
                list.load(true, 4);
            }
            hasList = true;
            final TLRPC.TL_channels_searchPosts req = new TLRPC.TL_channels_searchPosts();
            req.flags |= 1;
            req.hashtag = this.hashtagQuery = hashtag;
            req.limit = 10;
            if (lastRawMessage != null) {
                TLRPC.Message lastMessage = lastRawMessage;
                req.offset_id = lastMessage.id;
                req.offset_rate = lastRate;
                req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(lastMessage.peer_id);
            } else {
                req.offset_peer = new TLRPC.TL_inputPeerEmpty();
            }
            reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (id != searchId) {
                    return;
                }
                final boolean wasEmpty = messages.isEmpty();
                loading = false;
                if (res instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) res;
                    if (msgs instanceof TLRPC.TL_messages_messages) {
                        totalCount = ((TLRPC.TL_messages_messages) msgs).messages.size();
                    } else if (msgs instanceof TLRPC.TL_messages_messagesSlice) {
                        totalCount = ((TLRPC.TL_messages_messagesSlice) msgs).count;
                    }
                    if (!(msgs instanceof TLRPC.TL_messages_messages) && !(msgs instanceof TLRPC.TL_messages_messagesSlice)) totalCount = msgs.count;
                    lastRate = msgs.next_rate;
                    MessagesController.getInstance(currentAccount).putUsers(msgs.users, false);
                    MessagesController.getInstance(currentAccount).putChats(msgs.chats, false);
                    for (int i = 0; i < msgs.messages.size(); ++i) {
                        final TLRPC.Message msg = msgs.messages.get(i);
                        if (HiddenChatsController.getInstance(currentAccount).isHidden(MessageObject.getDialogId(msg))) continue;
                        final MessageObject messageObject = new MessageObject(currentAccount, msg, false, true);
                        messageObject.setQuery(finalQuery);
                        messages.add(messageObject);
                    }
                    lastRawMessage = msgs.messages.isEmpty() ? null : msgs.messages.get(msgs.messages.size() - 1);
                    rawLoadedCount += msgs.messages.size();
                    endReached = msgs.messages.isEmpty() || rawLoadedCount >= totalCount;
                    if (HiddenChatsController.getInstance(currentAccount).hasHiddenChats()) totalCount = messages.size();
                    if (!endReached && messages.isEmpty() && hiddenEmptyPages++ < 4) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (id == searchId && !loading) search(lastQuery);
                        }, 1);
                    }
                } else {
                    endReached = true;
                    totalCount = messages.size();
                }
                update(true);
                if (wasEmpty) {
                    scrollToTop(false);
                }
            }));
        }, 300);
    }

    public String getHashtag(String query) {
        return getHashtag(query, null);
    }

    public String getHashtag(String query, final boolean[] cashtag) {
        if (cashtag != null) cashtag[0] = false;
        if (query == null || query.isEmpty()) return null;
        String tquery = query.trim();
        if (tquery.length() <= 1) return null;
        if (tquery.charAt(0) != '#' && tquery.charAt(0) != '$') return null;
        if (tquery.indexOf('@') >= 0) return null;
        if (cashtag != null) cashtag[0] = tquery.charAt(0) == '$';
        return tquery.substring(1);
    }

    public void cancel() {
        if (list != null) {
            list.cancel();
        }
        hasList = false;
        if (reqId >= 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true);
            reqId = -1;
        }
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        searchId++;
        loading = false;
    }


    public void checkBottom() {
        if (!TextUtils.isEmpty(lastQuery)) {
            if (!endReached && !loading && !(messages.isEmpty() && hiddenEmptyPages > 4) && seesLoading()) {
                search(lastQuery);
            }
        }
    }

    public boolean seesLoading() {
        if (listView == null) return false;
        for (int i = 0; i < listView.getChildCount(); ++i) {
            View child = listView.getChildAt(i);
            if (child instanceof FlickerLoadingView) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
        update(false);
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
        super.onDetachedFromRecyclerView(recyclerView);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) update(true);
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        UItem item = getItem(holder.getAdapterPosition());
        if (item != null && item.object instanceof MessageObject && HiddenChatsController.getInstance(currentAccount).isHidden(((MessageObject) item.object).getDialogId())) return false;
        return super.isEnabled(holder);
    }

    protected void scrollToTop(boolean ifAtTop) {

    }

}
