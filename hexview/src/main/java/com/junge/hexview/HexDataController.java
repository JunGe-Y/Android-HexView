package com.junge.hexview;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class HexDataController {

    interface Callback {
        void onDataChanged(HexRenderModel model);
    }

    private final Handler mainHandler;
    private final Callback callback;
    private static final String PERF_TAG = "HexViewPerf";
    private static final long PERF_LOG_THRESHOLD_MS = 2L;

    private final int defaultWindowBytes;
    private final int prefetchWindowBytes;
    private final HexWindowCache cache = new HexWindowCache();

    private final Object lock = new Object();
    private ExecutorService loadExecutor = Executors.newSingleThreadExecutor();

    private HexSource source;
    private int sourceSize;
    private int bytesPerRow;
    private int windowStart = -1;
    private int windowEnd = -1;
    private int generation = 0;
    private int viewportRequestId = 0;
    private boolean multiWindowEnabled = true;
    private boolean shutdown = false;

    HexDataController(Handler mainHandler, Callback callback, int bytesPerRow, int defaultWindowBytes) {
        this.mainHandler = mainHandler;
        this.callback = callback;
        this.bytesPerRow = Math.max(1, bytesPerRow);
        this.defaultWindowBytes = Math.max(this.bytesPerRow, defaultWindowBytes);
        this.prefetchWindowBytes = Math.max(this.bytesPerRow * 16, this.defaultWindowBytes / 2);
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
            ensureExecutorLocked();
            this.bytesPerRow = Math.max(1, bytesPerRow);
            generation++;
            windowStart = windowEnd = -1;
            cache.clear();
        }
        reload();
    }

    void setSource(HexSource source) {
        long startTime = SystemClock.uptimeMillis();
        long sizeStartTime = SystemClock.uptimeMillis();
        int newSourceSize = source.size();
        long sizeCost = SystemClock.uptimeMillis() - sizeStartTime;
        synchronized (lock) {
            ensureExecutorLocked();
            closeSourceLocked();
            this.source = source;
            this.sourceSize = newSourceSize;
            generation++;
            windowStart = windowEnd = -1;
            multiWindowEnabled = sourceSize > defaultWindowBytes;
            cache.clear();
        }
        reload();
        logPerf("controller.setSource", startTime,
                "sourceSize=" + newSourceSize
                        + ", sizeCost=" + sizeCost
                        + ", multiWindow=" + multiWindowEnabled
                        + ", defaultWindow=" + defaultWindowBytes);
    }

    void clear() {
        synchronized (lock) {
            ensureExecutorLocked();
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
        long startTime = SystemClock.uptimeMillis();
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
            logPerf("controller.onViewportChangedCached", startTime,
                    "visible=" + visibleStartOffset + ".." + visibleEndOffset
                            + ", request=" + requestStart + ".." + requestEnd
                            + ", sourceSize=" + currentSourceSize
                            + ", requestId=" + requestId);
            return;
        }

        loadWindow(currentSource, currentGeneration, requestStart, requestEnd, currentBytesPerRow, currentSourceSize, requestId);
        logPerf("controller.onViewportChangedLoad", startTime,
                "visible=" + visibleStartOffset + ".." + visibleEndOffset
                        + ", request=" + requestStart + ".." + requestEnd
                        + ", sourceSize=" + currentSourceSize
                        + ", requestId=" + requestId);
    }

    void cancelPendingLoads() {
        synchronized (lock) {
            generation++;
            viewportRequestId++;
            windowStart = windowEnd = -1;
            cache.clear();
        }
    }

    void shutdown() {
        ExecutorService executorToShutdown;
        synchronized (lock) {
            closeSourceLocked();
            generation++;
            viewportRequestId++;
            windowStart = windowEnd = -1;
            cache.clear();
            shutdown = true;
            executorToShutdown = loadExecutor;
        }
        executorToShutdown.shutdownNow();
    }

    private void reload() {
        long startTime = SystemClock.uptimeMillis();
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
        logPerf("controller.reload", startTime,
                "sourceSize=" + currentSourceSize
                        + ", bytesPerRow=" + currentBytesPerRow
                        + ", requestId=" + requestId);
    }

    private void loadWindow(HexSource source, int requestGeneration, int start, int end, int requestBytesPerRow, int totalSize, int requestId) {
        long startTime = SystemClock.uptimeMillis();
        if (start >= end) {
            return;
        }
        final int loadStart = start;
        final int loadEnd = end;
        ExecutorService executor;
        synchronized (lock) {
            if (shutdown || generation != requestGeneration || requestId != viewportRequestId) {
                return;
            }
            ensureExecutorLocked();
            executor = loadExecutor;
        }
        try {
            executor.execute(() -> {
                long workerStartTime = SystemClock.uptimeMillis();
                long cacheStartTime = SystemClock.uptimeMillis();
                HexRenderModel cached = cache.getCovering(loadStart, loadEnd);
                long cacheCost = SystemClock.uptimeMillis() - cacheStartTime;
                HexRenderModel loadedModel;
                long readCost = 0L;
                long modelCost = 0L;
                if (cached != null) {
                    loadedModel = cached;
                } else {
                    long readStartTime = SystemClock.uptimeMillis();
                    byte[] data = source.read(loadStart, loadEnd - loadStart);
                    readCost = SystemClock.uptimeMillis() - readStartTime;
                    if (data.length == 0 && loadStart < totalSize) {
                        return;
                    }
                    long modelStartTime = SystemClock.uptimeMillis();
                    loadedModel = HexRenderModel.from(loadStart, data, totalSize, requestBytesPerRow);
                    modelCost = SystemClock.uptimeMillis() - modelStartTime;
                    cache.put(loadedModel);
                }
                HexRenderModel modelToPublish = loadedModel;
                logPerf("controller.loadWindowWorker", workerStartTime,
                        "request=" + loadStart + ".." + loadEnd
                                + ", bytes=" + (loadEnd - loadStart)
                                + ", total=" + totalSize
                                + ", cached=" + (cached != null)
                                + ", cacheCost=" + cacheCost
                                + ", readCost=" + readCost
                                + ", modelCost=" + modelCost
                                + ", rows=" + modelToPublish.rows.size()
                                + ", requestId=" + requestId);
                long postStartTime = SystemClock.uptimeMillis();
                mainHandler.post(() -> {
                    long publishStartTime = SystemClock.uptimeMillis();
                    synchronized (lock) {
                        if (shutdown || generation != requestGeneration || requestId != viewportRequestId) {
                            logPerf("controller.publishSkipped", publishStartTime,
                                    "requestId=" + requestId
                                            + ", generation=" + generation
                                            + ", requestGeneration=" + requestGeneration
                                            + ", shutdown=" + shutdown);
                            return;
                        }
                        windowStart = modelToPublish.startOffset;
                        windowEnd = modelToPublish.getWindowEndOffset();
                    }
                    callback.onDataChanged(modelToPublish);
                    logPerf("controller.publish", publishStartTime,
                            "request=" + modelToPublish.startOffset + ".." + modelToPublish.getWindowEndOffset()
                                    + ", rows=" + modelToPublish.rows.size()
                                    + ", requestId=" + requestId
                                    + ", postDelay=" + (publishStartTime - postStartTime));
                });
            });
            logPerf("controller.loadWindowSubmit", startTime,
                    "request=" + loadStart + ".." + loadEnd
                            + ", bytes=" + (loadEnd - loadStart)
                            + ", total=" + totalSize
                            + ", requestId=" + requestId);
        } catch (RejectedExecutionException ignored) {
            logPerf("controller.loadWindowRejected", startTime,
                    "request=" + loadStart + ".." + loadEnd
                            + ", requestId=" + requestId);
        }
    }

    private void ensureExecutorLocked() {
        if (!shutdown && !loadExecutor.isShutdown() && !loadExecutor.isTerminated()) {
            return;
        }
        loadExecutor = Executors.newSingleThreadExecutor();
        shutdown = false;
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

    private void logPerf(String stage, long startTime, String detail) {
        long cost = SystemClock.uptimeMillis() - startTime;
        if (cost >= PERF_LOG_THRESHOLD_MS) {
            Log.d(PERF_TAG,
                    stage + " cost=" + cost + "ms"
                            + ", controller=" + Integer.toHexString(System.identityHashCode(this))
                            + ", thread=" + Thread.currentThread().getName()
                            + ", " + detail);
        }
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

