package org.telegram.messenger.ayu.contacts;

/**
 * Ox-gram contact tracker: one recorded online/offline transition of a special contact.
 * Rows come from {@link ContactTrackerStorage}; {@link #timestamp} is wall clock milliseconds.
 */
public class ContactStatusEntry {

    public long rowId;
    public long userId;
    public int account;
    public boolean online;
    public long timestamp;

    public ContactStatusEntry() {
    }

    public ContactStatusEntry(long userId, int account, boolean online, long timestamp) {
        this.userId = userId;
        this.account = account;
        this.online = online;
        this.timestamp = timestamp;
    }
}
