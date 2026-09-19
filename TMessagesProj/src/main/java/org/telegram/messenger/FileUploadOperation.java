/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import org.telegram.messenger.ayu.upload.AyuUploadConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;

public class FileUploadOperation {

    private static class UploadCachedResult {
        private long bytesOffset;
        private byte[] iv;
    }

    /**
     * AyuGram Upload Accelerator: everything needed to re-send one single part without touching the
     * rest of the operation. Parts complete out of order, so each one carries its own file offset and
     * (for secret chats) the AES-IGE state the part has to be encrypted with.
     */
    private static class UploadPart {
        private int partNum;
        private int requestNum;
        private long fileOffset;
        private int plainBytes;
        /** IGE state *before* this part was encrypted, so the part can be rebuilt byte for byte */
        private byte[] ivBefore;
        /** IGE state *after* this part, borrowed from freeRequestIvs (upstream behaviour) */
        private byte[] ivAfter;
        /** private copy of {@link #ivAfter}: what has to be persisted as "_ivc" */
        private byte[] ivAfterCopy;
        private boolean ivReturned;
        private long bytesOffsetForSave;
        private int attempt;
        private int guid;
    }

    private int currentAccount;
    private boolean isLastPart;
    private boolean nextPartFirst;
    private int operationGuid;
    private static final int minUploadChunkSize = 128;
    private static final int minUploadChunkSlowNetworkSize = 32;
    private static final int initialRequestsCount = 8;
    private static final int initialRequestsSlowNetworkCount = 1;
    private static final int maxUploadingKBytes = 1024 * 2;
    private static final int maxUploadingSlowNetworkKBytes = 32;
    private static final int maxUploadChunkKBytes = 512; // largest part size upload.saveFilePart / saveBigFilePart accept
    private static final long uploadBoostMinFileSize = 2 * 1024 * 1024;
    /** AyuGram: upper bound of the exponential back-off between two attempts of the same part */
    private static final long maxPartRetryDelay = 30 * 1000L;

    private int maxRequestsCount;
    private int uploadChunkSize = 64 * 1024;
    private boolean slowNetwork;
    private int uploadConnectionsCount = 4;
    private ArrayList<byte[]> freeRequestIvs;
    private int requestNum;
    private String uploadingFilePath;
    private int state;
    private byte[] readBuffer;
    private FileUploadOperationDelegate delegate;
    public final SparseIntArray requestTokens = new SparseIntArray();
    public final ArrayList<Integer> uiRequestTokens = new ArrayList<>();
    private int currentPartNum;
    private long currentFileId;
    private long totalFileSize;
    private int totalPartsCount;
    private long readBytesCount;
    private long uploadedBytesCount;
    private int saveInfoTimes;
    private byte[] key;
    private byte[] iv;
    private byte[] ivChange;
    private boolean isEncrypted;
    private int fingerprint;
    private boolean isBigFile;
    private boolean forceSmallFile;
    private String fileKey;
    private long estimatedSize;
    private int uploadStartTime;
    private RandomAccessFile stream;
    private boolean started;
    private int currentUploadRequetsCount;
    private SharedPreferences preferences;
    private int currentType;
    private int lastSavedPartNum;
    private long availableSize;
    private boolean uploadFirstPartLater;
    private SparseArray<UploadCachedResult> cachedResults = new SparseArray<>();
    private boolean[] recalculatedEstimatedSize = {false, false};
    protected long lastProgressUpdateTime;

    // ------------------------------------------------ AyuGram Upload Accelerator
    /** set by the AyuGram upload queue; while true no new part request is issued */
    private volatile boolean paused;
    /** parts the server has acknowledged; persisted so a resume never sends the same part twice */
    private BitSet confirmedParts = new BitSet();
    /**
     * only big, unencrypted uploads may skip individual parts: the AES-IGE chain of a secret chat
     * upload has to be walked part by part, and a small (upload.saveFilePart) upload is re-read
     * sequentially on resume anyway.
     */
    private boolean trackConfirmedParts;
    private int confirmedPartsSaveCounter;

    public volatile boolean caughtPremiumFloodWait;

    public interface FileUploadOperationDelegate {
        void didFinishUploadingFile(FileUploadOperation operation, TLRPC.InputFile inputFile, TLRPC.InputEncryptedFile inputEncryptedFile, byte[] key, byte[] iv);
        void didFailedUploadingFile(FileUploadOperation operation);
        void didChangedUploadProgress(FileUploadOperation operation, long uploadedSize, long totalSize);
    }

    public FileUploadOperation(int instance, String location, boolean encrypted, long estimated, int type) {
        currentAccount = instance;
        uploadingFilePath = location;
        isEncrypted = encrypted;
        estimatedSize = estimated;
        currentType = type;
        uploadFirstPartLater = estimated != 0 && !isEncrypted;
    }

    public long getTotalFileSize() {
        return totalFileSize;
    }

    /** AyuGram: the path this operation uploads, used as the key of the AyuGram upload queue */
    public String getUploadingFilePath() {
        return uploadingFilePath;
    }

    /** AyuGram: true once {@link #start()} has been called, i.e. the operation holds a loader slot */
    public boolean isStarted() {
        return state != 0;
    }

    /** AyuGram: true while the AyuGram upload queue holds this operation back */
    public boolean isPausedByUser() {
        return paused;
    }

    public void setDelegate(FileUploadOperationDelegate fileUploadOperationDelegate) {
        delegate = fileUploadOperationDelegate;
    }

    public void start() {
        if (state != 0) {
            return;
        }
        state = 1;
        AutoDeleteMediaTask.lockFile(uploadingFilePath);
        Utilities.stageQueue.postRunnable(() -> {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences("uploadinfo", Activity.MODE_PRIVATE);
            slowNetwork = ApplicationLoader.isConnectionSlow();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("start upload on slow network = " + slowNetwork);
            }
            for (int a = 0, count = getInitialRequestsCount(); a < count; a++) {
                startUploadRequest();
            }
        });
    }

    protected void onNetworkChanged(final boolean slow) {
        if (state != 1) {
            return;
        }
        Utilities.stageQueue.postRunnable(() -> {
            if (slowNetwork != slow) {
                slowNetwork = slow;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("network changed to slow = " + slowNetwork);
                }
                for (int a = 0; a < requestTokens.size(); a++) {
                    ConnectionsManager.getInstance(currentAccount).cancelRequest(requestTokens.valueAt(a), true);
                }
                requestTokens.clear();
                // AyuGram: a network change used to wipe the resume information (cleanup() removes it)
                // and restart the whole file from byte 0. Keep the information instead: the chunk size
                // usually changes together with the network speed, and when it does the regular
                // "chunk size mismatch" check below still forces a clean restart.
                softReset();
                uploadFirstPartLater = false;

                for (int a = 0, count = getInitialRequestsCount(); a < count; a++) {
                    startUploadRequest();
                }
            }
        });
        AndroidUtilities.runOnUIThread(() -> uiRequestTokens.clear());
    }

    public void cancel() {
        if (state == 3) {
            return;
        }
        state = 2;
        Utilities.stageQueue.postRunnable(() -> {
            for (int a = 0; a < requestTokens.size(); a++) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(requestTokens.valueAt(a), true);
            }
        });
        AutoDeleteMediaTask.unlockFile(uploadingFilePath);
        delegate.didFailedUploadingFile(this);
        cleanup();
    }

    // ---------------------------------------------------------------- pause / resume

    /**
     * AyuGram: stops issuing part requests and cancels the ones in flight, but keeps every piece of
     * resume information (persisted offset + confirmed part bitmap) so that {@link #resumeUpload()}
     * continues from the last part the server confirmed instead of from the beginning of the file.
     */
    public void pauseUpload() {
        if (paused) {
            return;
        }
        paused = true;
        if (state != 1) {
            return;
        }
        Utilities.stageQueue.postRunnable(this::softReset);
    }

    /** AyuGram: counterpart of {@link #pauseUpload()} */
    public void resumeUpload() {
        if (!paused) {
            return;
        }
        paused = false;
        if (state != 1) {
            return;
        }
        Utilities.stageQueue.postRunnable(() -> {
            if (paused || state != 1) {
                return;
            }
            for (int a = 0, count = getInitialRequestsCount(); a < count; a++) {
                startUploadRequest();
            }
        });
    }

    /**
     * Drops every piece of in-memory progress but, unlike {@link #cleanup()}, keeps the "uploadinfo"
     * preferences, so the next {@link #startUploadRequest()} re-enters the init block and picks the
     * upload up where it stopped. Must run on {@code Utilities.stageQueue}.
     */
    private void softReset() {
        for (int a = 0; a < requestTokens.size(); a++) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestTokens.valueAt(a), true);
        }
        requestTokens.clear();
        AndroidUtilities.runOnUIThread(() -> uiRequestTokens.clear());
        flushConfirmedParts();
        try {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        isLastPart = false;
        nextPartFirst = false;
        requestNum = 0;
        currentPartNum = 0;
        readBytesCount = 0;
        uploadedBytesCount = 0;
        saveInfoTimes = 0;
        key = null;
        iv = null;
        ivChange = null;
        currentUploadRequetsCount = 0;
        lastSavedPartNum = 0;
        cachedResults.clear();
        operationGuid++;
    }

    private void cleanup() {
        if (preferences == null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences("uploadinfo", Activity.MODE_PRIVATE);
        }
        preferences.edit().remove(fileKey + "_time").
                remove(fileKey + "_size").
                remove(fileKey + "_uploaded").
                remove(fileKey + "_id").
                remove(fileKey + "_iv").
                remove(fileKey + "_key").
                remove(fileKey + "_chunk").
                remove(fileKey + "_parts").
                remove(fileKey + "_ivc").commit();
        confirmedParts.clear();
        try {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        AutoDeleteMediaTask.unlockFile(uploadingFilePath);
    }

    protected void checkNewDataAvailable(final long newAvailableSize, final long finalSize, final Float progress) {
        Utilities.stageQueue.postRunnable(() -> {
            if (progress != null && estimatedSize != 0 && finalSize == 0) {
                boolean needRecalculation = false;
                if (progress > 0.75f && !recalculatedEstimatedSize[0]) {
                    recalculatedEstimatedSize[0] = true;
                    needRecalculation = true;
                }
                if (progress > 0.95f && !recalculatedEstimatedSize[1]) {
                    recalculatedEstimatedSize[1] = true;
                    needRecalculation = true;
                }
                if (needRecalculation) {
                    estimatedSize = (long) (newAvailableSize / progress);
                }
            }

            if (estimatedSize != 0 && finalSize != 0) {
                estimatedSize = 0;
                totalFileSize = finalSize;
                calcTotalPartsCount();
                if (!uploadFirstPartLater && started) {
                    storeFileUploadInfo();
                }
            }
            availableSize = finalSize > 0 ? finalSize : newAvailableSize;
            if (currentUploadRequetsCount < maxRequestsCount) {
                startUploadRequest();
            }
        });
    }

    private int getInitialRequestsCount() {
        if (slowNetwork) {
            return initialRequestsSlowNetworkCount;
        }
        if (SharedConfig.enableUploadAccelerator) {
            return Math.max(initialRequestsCount, Math.min(16, SharedConfig.uploadThreadsCount));
        }
        return initialRequestsCount;
    }

    private void storeFileUploadInfo() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(fileKey + "_time", uploadStartTime);
        editor.putInt(fileKey + "_chunk", uploadChunkSize);
        editor.putLong(fileKey + "_size", totalFileSize);
        editor.putLong(fileKey + "_id", currentFileId);
        editor.remove(fileKey + "_uploaded");
        // a rewrite means a brand new file_id: every previously confirmed part is worthless now
        editor.remove(fileKey + "_parts");
        confirmedParts.clear();
        if (isEncrypted) {
            editor.putString(fileKey + "_iv", Utilities.bytesToHex(iv));
            editor.putString(fileKey + "_ivc", Utilities.bytesToHex(ivChange));
            editor.putString(fileKey + "_key", Utilities.bytesToHex(key));
        }
        editor.commit();
    }

    private void calcTotalPartsCount() {
        if (uploadFirstPartLater) {
            if (isBigFile) {
                totalPartsCount = 1 + (int) (((totalFileSize - uploadChunkSize) + uploadChunkSize - 1) / uploadChunkSize);
            } else {
                totalPartsCount = 1 + (int) (((totalFileSize - 1024) + uploadChunkSize - 1) / uploadChunkSize);
            }
        } else {
            totalPartsCount = (int) ((totalFileSize + uploadChunkSize - 1) / uploadChunkSize);
        }
    }

    public void setForceSmallFile() {
        forceSmallFile = true;
    }

    // ------------------------------------------------ confirmed parts bookkeeping

    private long partPlainSize(int partNum) {
        final long offset = (long) partNum * uploadChunkSize;
        if (offset >= totalFileSize) {
            return 0;
        }
        return Math.min(uploadChunkSize, totalFileSize - offset);
    }

    private int confirmedPrefix() {
        int prefix = 0;
        while (prefix < totalPartsCount && confirmedParts.get(prefix)) {
            prefix++;
        }
        return prefix;
    }

    private void markPartConfirmed(int partNum) {
        if (!trackConfirmedParts || partNum < 0) {
            return;
        }
        confirmedParts.set(partNum);
        confirmedPartsSaveCounter++;
        if (confirmedPartsSaveCounter >= 4) {
            confirmedPartsSaveCounter = 0;
            flushConfirmedParts();
        }
    }

    private void flushConfirmedParts() {
        if (!trackConfirmedParts || preferences == null || fileKey == null) {
            return;
        }
        try {
            final long offset = Math.min((long) confirmedPrefix() * uploadChunkSize, totalFileSize);
            preferences.edit()
                    .putString(fileKey + "_parts", Utilities.bytesToHex(confirmedParts.toByteArray()))
                    .putLong(fileKey + "_uploaded", offset)
                    .commit();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static BitSet bitSetFromHex(String hex) {
        if (hex == null || hex.length() == 0) {
            return new BitSet();
        }
        try {
            final byte[] bytes = Utilities.hexToBytes(hex);
            if (bytes == null) {
                return new BitSet();
            }
            return BitSet.valueOf(bytes);
        } catch (Throwable e) {
            FileLog.e(e);
            return new BitSet();
        }
    }

    // ------------------------------------------------------------- part requests

    private TLObject buildPartRequest(UploadPart part, NativeByteBuffer sendBuffer) {
        if (isBigFile) {
            TLRPC.TL_upload_saveBigFilePart req = new TLRPC.TL_upload_saveBigFilePart();
            req.file_part = part.partNum;
            req.file_id = currentFileId;
            if (estimatedSize != 0) {
                req.file_total_parts = -1;
            } else {
                req.file_total_parts = totalPartsCount;
            }
            req.bytes = sendBuffer;
            return req;
        } else {
            TLRPC.TL_upload_saveFilePart req = new TLRPC.TL_upload_saveFilePart();
            req.file_part = part.partNum;
            req.file_id = currentFileId;
            req.bytes = sendBuffer;
            return req;
        }
    }

    private void sendPart(final UploadPart part, final TLObject finalRequest) {
        final int requestSize = finalRequest.getObjectSize() + 4;

        int connectionType;
        if (slowNetwork) {
            connectionType = ConnectionsManager.ConnectionTypeUpload;
        } else {
            connectionType = ConnectionsManager.ConnectionTypeUpload | ((part.requestNum % uploadConnectionsCount) << 16);
        }
        final int[] requestToken = new int[1];
        requestToken[0] = ConnectionsManager.getInstance(currentAccount).sendRequest(finalRequest, (response, error) -> {
            if (part.guid != operationGuid) {
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("debug_uploading: " + " response reqId " + requestToken[0] + " time" + uploadingFilePath);
            }
            int networkType = response != null ? response.networkType : ApplicationLoader.getCurrentNetworkType();
            if (currentType == ConnectionsManager.FileTypeAudio) {
                StatsController.getInstance(currentAccount).incrementSentBytesCount(networkType, StatsController.TYPE_AUDIOS, requestSize);
            } else if (currentType == ConnectionsManager.FileTypeVideo) {
                StatsController.getInstance(currentAccount).incrementSentBytesCount(networkType, StatsController.TYPE_VIDEOS, requestSize);
            } else if (currentType == ConnectionsManager.FileTypePhoto) {
                StatsController.getInstance(currentAccount).incrementSentBytesCount(networkType, StatsController.TYPE_PHOTOS, requestSize);
            } else if (currentType == ConnectionsManager.FileTypeFile) {
                if (uploadingFilePath != null && (uploadingFilePath.toLowerCase().endsWith("mp3") || uploadingFilePath.toLowerCase().endsWith("m4a"))) {
                    StatsController.getInstance(currentAccount).incrementSentBytesCount(networkType, StatsController.TYPE_MUSIC, requestSize);
                } else {
                    StatsController.getInstance(currentAccount).incrementSentBytesCount(networkType, StatsController.TYPE_FILES, requestSize);
                }
            }
            if (part.ivAfter != null && !part.ivReturned && freeRequestIvs != null) {
                part.ivReturned = true;
                freeRequestIvs.add(part.ivAfter);
            }
            requestTokens.delete(part.requestNum);
            AndroidUtilities.runOnUIThread(() -> uiRequestTokens.remove((Integer) requestToken[0]));
            if (response instanceof TLRPC.TL_boolTrue) {
                onPartUploaded(part);
            } else {
                onPartFailed(part, error);
            }
        }, null, () -> Utilities.stageQueue.postRunnable(() -> {
            if (currentUploadRequetsCount < maxRequestsCount) {
                startUploadRequest();
            }
        }), forceSmallFile ? ConnectionsManager.RequestFlagCanCompress : 0, ConnectionsManager.DEFAULT_DATACENTER_ID, connectionType, true);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("debug_uploading: " + " send reqId " + requestToken[0] + " " + uploadingFilePath + " file_part=" + part.partNum + " isBig=" + isBigFile + " file_id=" + currentFileId + " attempt=" + part.attempt);
        }
        requestTokens.put(part.requestNum, requestToken[0]);
        AndroidUtilities.runOnUIThread(() -> uiRequestTokens.add(requestToken[0]));
    }

    private void onPartUploaded(UploadPart part) {
        if (state != 1) {
            return;
        }
        uploadedBytesCount += part.plainBytes;
        long size;
        if (estimatedSize != 0) {
            size = Math.max(availableSize, estimatedSize);
        } else {
            size = totalFileSize;
        }
        delegate.didChangedUploadProgress(FileUploadOperation.this, uploadedBytesCount, size);
        currentUploadRequetsCount--;
        markPartConfirmed(part.partNum);
        if (isLastPart && currentUploadRequetsCount == 0 && state == 1) {
            finishUploadOperation();
        } else if (currentUploadRequetsCount < maxRequestsCount) {
            if (!trackConfirmedParts && estimatedSize == 0 && !uploadFirstPartLater && !nextPartFirst) {
                if (saveInfoTimes >= 4) {
                    saveInfoTimes = 0;
                }
                if (part.partNum == lastSavedPartNum) {
                    lastSavedPartNum++;
                    long offsetToSave = part.bytesOffsetForSave;
                    byte[] ivToSave = part.ivAfterCopy;
                    UploadCachedResult result;
                    while ((result = cachedResults.get(lastSavedPartNum)) != null) {
                        offsetToSave = result.bytesOffset;
                        ivToSave = result.iv;
                        cachedResults.remove(lastSavedPartNum);
                        lastSavedPartNum++;
                    }
                    if (isBigFile && offsetToSave % (1024 * 1024) == 0 || !isBigFile && saveInfoTimes == 0) {
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putLong(fileKey + "_uploaded", offsetToSave);
                        if (isEncrypted && ivToSave != null) {
                            editor.putString(fileKey + "_ivc", Utilities.bytesToHex(ivToSave));
                        }
                        editor.commit();
                    }
                } else {
                    UploadCachedResult result = new UploadCachedResult();
                    result.bytesOffset = part.bytesOffsetForSave;
                    if (part.ivAfterCopy != null) {
                        result.iv = new byte[32];
                        System.arraycopy(part.ivAfterCopy, 0, result.iv, 0, 32);
                    }
                    cachedResults.put(part.partNum, result);
                }
                saveInfoTimes++;
            }
            startUploadRequest();
        }
    }

    /**
     * AyuGram: one part failed. Unless the error is fatal the part alone is queued for another
     * attempt with exponential back-off; the operation, its file id and every other part stay alive.
     */
    private void onPartFailed(UploadPart part, TLRPC.TL_error error) {
        if (state != 1) {
            return;
        }
        if (error != null && error.text != null && error.text.startsWith("FLOOD_PREMIUM_WAIT")) {
            caughtPremiumFloodWait = true;
        }
        final int maxRetries = AyuUploadConfig.getPartRetries();
        if (maxRetries > 0 && part.attempt < maxRetries && isRetryablePart(part, error)) {
            part.attempt++;
            final int guid = part.guid;
            final long delay = retryDelay(part.attempt, error);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("debug_uploading: part " + part.partNum + " failed (" + (error != null ? error.code + " " + error.text : "no response") + "), retry " + part.attempt + "/" + maxRetries + " in " + delay + "ms");
            }
            Utilities.stageQueue.postRunnable(() -> {
                if (guid != operationGuid || state != 1 || paused) {
                    // a pause / network change already rewound the operation; the part is not
                    // confirmed, so it will simply be sent again from the resumed read position
                    return;
                }
                resendPart(part);
            }, delay);
            return;
        }
        state = 4;
        delegate.didFailedUploadingFile(FileUploadOperation.this);
        cleanup();
    }

    private boolean isRetryablePart(UploadPart part, TLRPC.TL_error error) {
        if (isEncrypted && part.ivBefore == null) {
            // without the IGE state of this exact part we cannot rebuild its ciphertext
            return false;
        }
        if (error == null) {
            return true;
        }
        if (error.code < 0 || error.code >= 500) {
            return true;
        }
        final String text = error.text;
        if (text == null) {
            return true;
        }
        if (text.startsWith("FLOOD_WAIT_") || text.startsWith("FLOOD_PREMIUM_WAIT")) {
            return true;
        }
        if (text.startsWith("TIMEOUT") || text.startsWith("MSG_WAIT_FAILED") || text.startsWith("RPC_")
                || text.startsWith("INTERNAL") || text.startsWith("FILE_WRITE_FAILED")
                || text.startsWith("PERSISTENT_TIMESTAMP")) {
            return true;
        }
        return false;
    }

    private long retryDelay(int attempt, TLRPC.TL_error error) {
        if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
            try {
                final int seconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length()));
                return Math.max(1000L, Math.min(60 * 1000L, seconds * 1000L));
            } catch (Exception ignore) {
            }
        }
        long delay = 1000L << Math.min(5, Math.max(0, attempt - 1));
        return Math.min(maxPartRetryDelay, delay);
    }

    /** rebuilds the ciphertext / plaintext of a single part and sends it again */
    private void resendPart(UploadPart part) {
        if (state != 1 || paused) {
            return;
        }
        if (trackConfirmedParts && confirmedParts.get(part.partNum)) {
            currentUploadRequetsCount--;
            if (currentUploadRequetsCount < maxRequestsCount) {
                startUploadRequest();
            }
            return;
        }
        final NativeByteBuffer sendBuffer;
        try {
            if (stream == null) {
                throw new Exception("upload stream is gone");
            }
            final long savedPosition = stream.getFilePointer();
            stream.seek(part.fileOffset);
            final byte[] buffer = new byte[part.plainBytes];
            int offset = 0;
            while (offset < part.plainBytes) {
                final int read = stream.read(buffer, offset, part.plainBytes - offset);
                if (read <= 0) {
                    break;
                }
                offset += read;
            }
            stream.seek(savedPosition);
            if (offset != part.plainBytes) {
                throw new Exception("could not re-read upload part " + part.partNum);
            }
            int toAdd = 0;
            if (isEncrypted && part.plainBytes % 16 != 0) {
                toAdd += 16 - part.plainBytes % 16;
            }
            sendBuffer = new NativeByteBuffer(part.plainBytes + toAdd);
            sendBuffer.writeBytes(buffer, 0, part.plainBytes);
            if (isEncrypted) {
                for (int a = 0; a < toAdd; a++) {
                    sendBuffer.writeByte(0);
                }
                final byte[] ivCopy = new byte[32];
                System.arraycopy(part.ivBefore, 0, ivCopy, 0, 32);
                Utilities.aesIgeEncryption(sendBuffer.buffer, key, ivCopy, true, true, 0, part.plainBytes + toAdd);
            }
        } catch (Exception e) {
            FileLog.e(e);
            state = 4;
            delegate.didFailedUploadingFile(this);
            cleanup();
            return;
        }
        part.requestNum = requestNum++;
        part.guid = operationGuid;
        sendPart(part, buildPartRequest(part, sendBuffer));
    }

    private void finishUploadOperation() {
        state = 3;
        if (key == null) {
            TLRPC.InputFile result;
            if (isBigFile) {
                result = new TLRPC.TL_inputFileBig();
            } else {
                result = new TLRPC.TL_inputFile();
                result.md5_checksum = "";
            }
            result.parts = currentPartNum;
            result.id = currentFileId;
            result.name = uploadingFilePath.substring(uploadingFilePath.lastIndexOf("/") + 1);
            delegate.didFinishUploadingFile(FileUploadOperation.this, result, null, null, null);
            cleanup();
        } else {
            TLRPC.InputEncryptedFile result;
            if (isBigFile) {
                result = new TLRPC.TL_inputEncryptedFileBigUploaded();
            } else {
                result = new TLRPC.TL_inputEncryptedFileUploaded();
                result.md5_checksum = "";
            }
            result.parts = currentPartNum;
            result.id = currentFileId;
            result.key_fingerprint = fingerprint;
            delegate.didFinishUploadingFile(FileUploadOperation.this, null, result, key, iv);
            cleanup();
        }
        if (currentType == ConnectionsManager.FileTypeAudio) {
            StatsController.getInstance(currentAccount).incrementSentItemsCount(ApplicationLoader.getCurrentNetworkType(), StatsController.TYPE_AUDIOS, 1);
        } else if (currentType == ConnectionsManager.FileTypeVideo) {
            StatsController.getInstance(currentAccount).incrementSentItemsCount(ApplicationLoader.getCurrentNetworkType(), StatsController.TYPE_VIDEOS, 1);
        } else if (currentType == ConnectionsManager.FileTypePhoto) {
            StatsController.getInstance(currentAccount).incrementSentItemsCount(ApplicationLoader.getCurrentNetworkType(), StatsController.TYPE_PHOTOS, 1);
        } else if (currentType == ConnectionsManager.FileTypeFile) {
            if (uploadingFilePath != null && (uploadingFilePath.toLowerCase().endsWith("mp3") || uploadingFilePath.toLowerCase().endsWith("m4a"))) {
                StatsController.getInstance(currentAccount).incrementSentItemsCount(ApplicationLoader.getCurrentNetworkType(), StatsController.TYPE_MUSIC, 1);
            } else {
                StatsController.getInstance(currentAccount).incrementSentItemsCount(ApplicationLoader.getCurrentNetworkType(), StatsController.TYPE_FILES, 1);
            }
        }
    }

    private void startUploadRequest() {
        if (state != 1 || paused) {
            return;
        }

        final TLObject finalRequest;
        final UploadPart part = new UploadPart();

        final int currentRequestBytes;
        final byte[] currentRequestIv;
        try {
            started = true;
            if (stream == null) {
                File cacheFile = new File(uploadingFilePath);
//                if (AndroidUtilities.isInternalUri(Uri.fromFile(cacheFile))) {
//                    throw new FileLog.IgnoreSentException("trying to upload internal file");
//                }
                stream = new RandomAccessFile(cacheFile, "r");
                boolean isInternalFile = false;
                try {
                    @SuppressLint("DiscouragedPrivateApi") Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                    int fdint = (Integer) getInt.invoke(stream.getFD());
                    if (AndroidUtilities.isInternalUri(fdint)) {
                        isInternalFile = true;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                if (isInternalFile) {
                    throw new Exception("trying to upload internal file");
                }
                if (estimatedSize != 0) {
                    totalFileSize = estimatedSize;
                } else {
                    totalFileSize = cacheFile.length();
                }
                if (!forceSmallFile && totalFileSize > 10 * 1024 * 1024) {
                    isBigFile = true;
                }

                long maxUploadParts = MessagesController.getInstance(currentAccount).uploadMaxFileParts;
                if (AccountInstance.getInstance(currentAccount).getUserConfig().isRealPremium() && totalFileSize > FileLoader.DEFAULT_MAX_FILE_SIZE) {
                    maxUploadParts = MessagesController.getInstance(currentAccount).uploadMaxFilePartsPremium;
                }
                uploadChunkSize = (int) Math.max(slowNetwork ? minUploadChunkSlowNetworkSize : minUploadChunkSize, (totalFileSize + 1024L * maxUploadParts - 1) / (1024L * maxUploadParts));
                if (1024 % uploadChunkSize != 0) {
                    int chunkSize = 64;
                    while (uploadChunkSize > chunkSize) {
                        chunkSize *= 2;
                    }
                    uploadChunkSize = chunkSize;
                }
                boolean uploadBoost = SharedConfig.enableUploadAccelerator && !slowNetwork && !forceSmallFile && totalFileSize >= uploadBoostMinFileSize;
                if (uploadBoost) {
                    int threads = Math.max(4, Math.min(16, SharedConfig.uploadThreadsCount));
                    uploadChunkSize = Math.max(uploadChunkSize, maxUploadChunkKBytes);
                    maxRequestsCount = threads;
                    uploadConnectionsCount = Math.min(ConnectionsManager.UploadConnectionsCount, threads);
                } else {
                    maxRequestsCount = Math.max(1, (slowNetwork ? maxUploadingSlowNetworkKBytes : maxUploadingKBytes) / uploadChunkSize);
                    uploadConnectionsCount = 4;
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("debug_uploading: boost=" + uploadBoost + " chunk=" + uploadChunkSize + "KB requests=" + maxRequestsCount + " connections=" + uploadConnectionsCount);
                }

                if (isEncrypted) {
                    freeRequestIvs = new ArrayList<>(maxRequestsCount);
                    for (int a = 0; a < maxRequestsCount; a++) {
                        freeRequestIvs.add(new byte[32]);
                    }
                }

                uploadChunkSize *= 1024;
                calcTotalPartsCount();
                readBuffer = new byte[uploadChunkSize];

                fileKey = Utilities.MD5(uploadingFilePath + (isEncrypted ? "enc" : ""));
                // AyuGram: only a plain big-file upload can skip individual confirmed parts
                trackConfirmedParts = AyuUploadConfig.isReuseConfirmedPartsEnabled()
                        && isBigFile && !isEncrypted && estimatedSize == 0 && !uploadFirstPartLater && !nextPartFirst;
                confirmedParts.clear();
                long fileSize = preferences.getLong(fileKey + "_size", 0);
                uploadStartTime = (int)(System.currentTimeMillis() / 1000);
                boolean rewrite = false;
                if (!uploadFirstPartLater && !nextPartFirst && estimatedSize == 0 && fileSize == totalFileSize && preferences.getInt(fileKey + "_chunk", -1) == uploadChunkSize) {
                    currentFileId = preferences.getLong(fileKey + "_id", 0);
                    int date = preferences.getInt(fileKey + "_time", 0);
                    long uploadedSize = preferences.getLong(fileKey + "_uploaded", 0);
                    if (uploadedSize > 0 && uploadedSize % uploadChunkSize != 0 && uploadedSize != totalFileSize) {
                        // not a whole number of parts: the value cannot be trusted as a read offset
                        uploadedSize = 0;
                    }
                    // AyuGram: the set of parts the server confirmed for this exact file id
                    BitSet persistedParts = null;
                    if (trackConfirmedParts) {
                        persistedParts = bitSetFromHex(preferences.getString(fileKey + "_parts", null));
                    }
                    final boolean hasPersistedParts = persistedParts != null && !persistedParts.isEmpty();
                    if (isEncrypted) {
                        String ivString = preferences.getString(fileKey + "_iv", null);
                        String keyString = preferences.getString(fileKey + "_key", null);
                        if (ivString != null && keyString != null) {
                            key = Utilities.hexToBytes(keyString);
                            iv = Utilities.hexToBytes(ivString);
                            if (key != null && iv != null && key.length == 32 && iv.length == 32) {
                                ivChange = new byte[32];
                                System.arraycopy(iv, 0, ivChange, 0, 32);
                            } else {
                                rewrite = true;
                            }
                        } else {
                            rewrite = true;
                        }
                    }
                    if (!rewrite && date != 0) {
                        if (isBigFile && date < uploadStartTime - 60 * 60 * 24) {
                            date = 0;
                        } else if (!isBigFile && date < uploadStartTime - 60 * 60 * 1.5f) {
                            date = 0;
                        }
                        if (date != 0) {
                            if (uploadedSize > 0 || hasPersistedParts) {
                                readBytesCount = uploadedSize;
                                currentPartNum = (int) (uploadedSize / uploadChunkSize);
                                if (persistedParts != null) {
                                    confirmedParts = persistedParts;
                                    // everything before the persisted offset is confirmed by definition
                                    for (int b = 0; b < currentPartNum; b++) {
                                        confirmedParts.set(b);
                                    }
                                }
                                if (!isBigFile) {
                                    for (int b = 0; b < readBytesCount / uploadChunkSize; b++) {
                                        int bytesRead = stream.read(readBuffer);
                                        int toAdd = 0;
                                        if (isEncrypted && bytesRead % 16 != 0) {
                                            toAdd += 16 - bytesRead % 16;
                                        }
                                        NativeByteBuffer sendBuffer = new NativeByteBuffer(bytesRead + toAdd);
                                        if (bytesRead != uploadChunkSize || totalPartsCount == currentPartNum + 1) {
                                            isLastPart = true;
                                        }
                                        sendBuffer.writeBytes(readBuffer, 0, bytesRead);
                                        if (isEncrypted) {
                                            for (int a = 0; a < toAdd; a++) {
                                                sendBuffer.writeByte(0);
                                            }
                                            Utilities.aesIgeEncryption(sendBuffer.buffer, key, ivChange, true, true, 0, bytesRead + toAdd);
                                        }
                                        sendBuffer.reuse();
                                    }
                                } else {
                                    stream.seek(uploadedSize);
                                    if (isEncrypted) {
                                        String ivcString = preferences.getString(fileKey + "_ivc", null);
                                        if (ivcString != null) {
                                            ivChange = Utilities.hexToBytes(ivcString);
                                            if (ivChange == null || ivChange.length != 32) {
                                                rewrite = true;
                                                readBytesCount = 0;
                                                currentPartNum = 0;
                                            }
                                        } else {
                                            rewrite = true;
                                            readBytesCount = 0;
                                            currentPartNum = 0;
                                        }
                                    }
                                }
                            } else {
                                rewrite = true;
                            }
                        }
                    } else {
                        rewrite = true;
                    }
                } else {
                    rewrite = true;
                }
                if (rewrite) {
                    confirmedParts.clear();
                    readBytesCount = 0;
                    currentPartNum = 0;
                    if (isEncrypted) {
                        iv = new byte[32];
                        key = new byte[32];
                        ivChange = new byte[32];
                        Utilities.random.nextBytes(iv);
                        Utilities.random.nextBytes(key);
                        System.arraycopy(iv, 0, ivChange, 0, 32);
                    }
                    currentFileId = Utilities.random.nextLong();
                    if (!nextPartFirst && !uploadFirstPartLater && estimatedSize == 0) {
                        storeFileUploadInfo();
                    }
                }

                if (isEncrypted) {
                    try {
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                        byte[] arr = new byte[64];
                        System.arraycopy(key, 0, arr, 0, 32);
                        System.arraycopy(iv, 0, arr, 32, 32);
                        byte[] digest = md.digest(arr);
                        for (int a = 0; a < 4; a++) {
                            fingerprint |= ((digest[a] ^ digest[a + 4]) & 0xFF) << (a * 8);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                uploadedBytesCount = readBytesCount;
                if (trackConfirmedParts) {
                    // parts that were confirmed out of order already count towards the progress
                    for (int b = currentPartNum; b < totalPartsCount; b++) {
                        if (confirmedParts.get(b)) {
                            uploadedBytesCount += partPlainSize(b);
                        }
                    }
                }
                lastSavedPartNum = currentPartNum;

                if (uploadFirstPartLater) {
                    if (isBigFile) {
                        stream.seek(uploadChunkSize);
                        readBytesCount = uploadChunkSize;
                    } else {
                        stream.seek(1024);
                        readBytesCount = 1024;
                    }
                    currentPartNum = 1;
                }
            }

            // AyuGram: never send a part the server has already confirmed
            if (trackConfirmedParts && !nextPartFirst && !confirmedParts.isEmpty()) {
                boolean skipped = false;
                while (currentPartNum < totalPartsCount && confirmedParts.get(currentPartNum)) {
                    currentPartNum++;
                    skipped = true;
                }
                if (skipped) {
                    readBytesCount = Math.min((long) currentPartNum * uploadChunkSize, totalFileSize);
                    stream.seek(readBytesCount);
                    lastSavedPartNum = Math.max(lastSavedPartNum, currentPartNum);
                }
                if (currentPartNum >= totalPartsCount) {
                    isLastPart = true;
                    if (currentUploadRequetsCount == 0 && state == 1) {
                        finishUploadOperation();
                    }
                    return;
                }
            }

            if (estimatedSize != 0) {
                if (readBytesCount + uploadChunkSize > availableSize) {
                    return;
                }
            }

            if (nextPartFirst) {
                stream.seek(0);
                part.fileOffset = 0;
                if (isBigFile) {
                    currentRequestBytes = stream.read(readBuffer);
                } else {
                    currentRequestBytes = stream.read(readBuffer, 0, 1024);
                }
                currentPartNum = 0;
            } else {
                part.fileOffset = stream.getFilePointer();
                currentRequestBytes = stream.read(readBuffer);
            }
            if (currentRequestBytes == -1) {
                return;
            }
            int toAdd = 0;
            if (isEncrypted && currentRequestBytes % 16 != 0) {
                toAdd += 16 - currentRequestBytes % 16;
            }
            NativeByteBuffer sendBuffer = new NativeByteBuffer(currentRequestBytes + toAdd);
            if (nextPartFirst || currentRequestBytes != uploadChunkSize || estimatedSize == 0 && totalPartsCount == currentPartNum + 1) {
                if (uploadFirstPartLater) {
                    nextPartFirst = true;
                    uploadFirstPartLater = false;
                } else {
                    isLastPart = true;
                }
            }
            sendBuffer.writeBytes(readBuffer, 0, currentRequestBytes);
            if (isEncrypted) {
                if (AyuUploadConfig.getPartRetries() > 0) {
                    part.ivBefore = new byte[32];
                    System.arraycopy(ivChange, 0, part.ivBefore, 0, 32);
                }
                for (int a = 0; a < toAdd; a++) {
                    sendBuffer.writeByte(0);
                }
                Utilities.aesIgeEncryption(sendBuffer.buffer, key, ivChange, true, true, 0, currentRequestBytes + toAdd);
                currentRequestIv = freeRequestIvs.get(0);
                System.arraycopy(ivChange, 0, currentRequestIv, 0, 32);
                freeRequestIvs.remove(0);
                part.ivAfterCopy = new byte[32];
                System.arraycopy(currentRequestIv, 0, part.ivAfterCopy, 0, 32);
            } else {
                currentRequestIv = null;
            }
            part.partNum = currentPartNum;
            part.plainBytes = currentRequestBytes;
            part.ivAfter = currentRequestIv;
            part.bytesOffsetForSave = (long) currentPartNum * uploadChunkSize + currentRequestBytes;
            finalRequest = buildPartRequest(part, sendBuffer);
            if (isLastPart && nextPartFirst) {
                nextPartFirst = false;
                currentPartNum = totalPartsCount - 1;
                stream.seek(totalFileSize);
            }
            readBytesCount += currentRequestBytes;
        } catch (Exception e) {
            FileLog.e(e);
            state = 4;
            delegate.didFailedUploadingFile(this);
            cleanup();
            return;
        }
        currentPartNum++;
        currentUploadRequetsCount++;
        part.requestNum = requestNum++;
        part.guid = operationGuid;
        sendPart(part, finalRequest);
    }
}
