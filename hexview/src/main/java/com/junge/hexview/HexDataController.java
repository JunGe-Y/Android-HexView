package com.junge.hexview;

import android.os.Handler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HexDataController {

    interface Callback {
        void onDataChanged(HexRenderModel model);
    }

    private final Handler mainHandler;
    private final Callback callback;
    private final int defaultWindowBytes;
    private final int prefetchWindowBytes;
    private final HexWindowCache cache = new HexWindowCache();
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();

    private final Object lock = new Object();

    private HexSource source;
    private int sourceSize;
    private int bytesPerRow;
    private int windowStart = -1;
    private int windowEnd = -1;
    private int generation = 0;
    private int viewportRequestId = 0;
    private boolean multiWindowEnabled = true;

    HexDataController(Handler mainHandler, Callback callback, int bytesPerRow, int defaultWindowBytes) {
        this.mainHandler = mainHandler;
        this.callback = callback;
        this.bytesPerRow = Math.max(1, bytesPerRow);
        this.defaultWindowBytes = Math.max(this.bytesPerRow, defaultWindowBytes);
        this.prefetchWindowBytes = Math.max(this.bytesPerRow * 64, this.defaultWindowBytes / 2);
    }

    int getBytesPerRow() {
        synchronized (lock) {
            return bytesPerRow;
        }
    }

    HexSource getSource() {
        synchronized (lock) {
            return source;
        }
    }

    int getSourceSize() {
        synchronized (lock) {
            return sourceSize;
        }
    }


    byte[] readRange(int offset, int length) {
        synchronized (lock) {
            if (source == null || length <= 0 || offset < 0 || offset >= sourceSize) {
                return new byte[0];
            }
            int safeLength = Math.min(length, sourceSize - offset);
            if (safeLength <= 0) {
                return new byte[0];
            }
            return source.read(offset, safeLength);
        }
    }

    void setBytesPerRow(int bytesPerRow) {
        synchronized (lock) {
            this.bytesPerRow = Math.max(1, bytesPerRow);
            generation++;
            windowStart = windowEnd = -1;
            cache.clear();
        }
        reload();
    }

    void setSource(HexSource source) {
        synchronized (lock) {
            closeSourceLocked();
            this.source = source;
            this.sourceSize = source.size();
            generation++;
            windowStart = windowEnd = -1;
            multiWindowEnabled = sourceSize > defaultWindowBytes;
            cache.clear();
        }
        reload();
    }

    void clear() {
        synchronized (lock) {
            closeSourceLocked();
            source = null;
            sourceSize = 0;
            generation++;
            windowStart = windowEnd = -1;
            multiWindowEnabled = true;
            cache.clear();
        }
        callback.onDataChanged(HexRenderModel.empty());
    }

    void onViewportChanged(int visibleStartOffset, int visibleEndOffset) {
        HexSource currentSource;
        int currentGeneration;
        int currentBytesPerRow;
        int currentSourceSize;
        boolean currentMultiWindowEnabled;
        synchronized (lock) {
            currentSource = source;
            currentGeneration = generation;
            currentBytesPerRow = bytesPerRow;
            currentSourceSize = sourceSize;
            currentMultiWindowEnabled = multiWindowEnabled;
        }
        if (currentSource == null || currentSourceSize <= 0) {
            return;
        }

        int alignedVisibleStart = alignDown(Math.max(0, visibleStartOffset), currentBytesPerRow);
        int alignedVisibleEnd = Math.min(currentSourceSize, alignUp(Math.max(alignedVisibleStart + currentBytesPerRow, visibleEndOffset), currentBytesPerRow));

        int requestStart;
        int requestEnd;
        if (!currentMultiWindowEnabled) {
            requestStart = 0;
            requestEnd = currentSourceSize;
        } else {
            requestStart = alignDown(Math.max(0, alignedVisibleStart - prefetchWindowBytes), currentBytesPerRow);
            requestEnd = Math.min(currentSourceSize, alignUp(alignedVisibleEnd + prefetchWindowBytes, currentBytesPerRow));
        }

        HexRenderModel cached = cache.getCovering(requestStart, requestEnd);
        final int requestId;
        synchronized (lock) {
            viewportRequestId++;
            requestId = viewportRequestId;
        }
        if (cached != null) {
            synchronized (lock) {
                if (requestId != viewportRequestId) {
                    return;
                }
                windowStart = cached.startOffset;
                windowEnd = cached.getWindowEndOffset();
            }
            callback.onDataChanged(cached);
            return;
        }

        loadWindow(currentSource, currentGeneration, requestStart, requestEnd, currentBytesPerRow, currentSourceSize, requestId);
    }

    void shutdown() {
        synchronized (lock) {
            closeSourceLocked();
        }
        loadExecutor.shutdownNow();
    }

    private void reload() {
        HexSource currentSource;
        int currentGeneration;
        int currentBytesPerRow;
        int currentSourceSize;
        synchronized (lock) {
            currentSource = source;
            currentGeneration = generation;
            currentBytesPerRow = bytesPerRow;
            currentSourceSize = sourceSize;
        }
        if (currentSource == null) {
            callback.onDataChanged(HexRenderModel.empty());
            return;
        }
        final int requestId;
        synchronized (lock) {
            viewportRequestId++;
            requestId = viewportRequestId;
        }
        loadWindow(currentSource, currentGeneration, 0, Math.min(currentSourceSize, defaultWindowBytes), currentBytesPerRow, currentSourceSize, requestId);
    }

    private void loadWindow(HexSource source, int requestGeneration, int start, int end, int requestBytesPerRow, int totalSize, int requestId) {
        if (start >= end) {
            return;
        }
        final int loadStart = start;
        final int loadEnd = end;
        loadExecutor.execute(() -> {
            HexRenderModel cached = cache.getCovering(loadStart, loadEnd);
            HexRenderModel loadedModel;
            if (cached != null) {
                loadedModel = cached;
            } else {
                byte[] data = source.read(loadStart, loadEnd - loadStart);
                if (data.length == 0 && loadStart < totalSize) {
                    return;
                }
                loadedModel = HexRenderModel.from(loadStart, data, totalSize, requestBytesPerRow);
                cache.put(loadedModel);
            }
            HexRenderModel modelToPublish = loadedModel;
            mainHandler.post(() -> {
                synchronized (lock) {
                    if (generation != requestGeneration || requestId != viewportRequestId) {
                        return;
                    }
                    windowStart = modelToPublish.startOffset;
                    windowEnd = modelToPublish.getWindowEndOffset();
                }
                callback.onDataChanged(modelToPublish);
            });
        });
    }

    private void closeSourceLocked() {
        if (source instanceof AutoCloseable) {
            try {
                ((AutoCloseable) source).close();
            } catch (Exception ignored) {
            }
        }
        source = null;
        sourceSize = 0;
    }

    private int alignDown(int value, int alignment) {
        int safeAlignment = Math.max(1, alignment);
        return value - value % safeAlignment;
    }

    private int alignUp(int value, int alignment) {
        int safeAlignment = Math.max(1, alignment);
        int remainder = value % safeAlignment;
        return remainder == 0 ? value : value + safeAlignment - remainder;
    }
}

