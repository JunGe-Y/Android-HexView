package com.junge.hexview;

final class HexSelectionController {

    private long selectionStart = -1;
    private long selectionEnd = -1;
    private long cursorOffset = -1;
    private boolean handlesVisible = false;

    void setCaret(long offset) {
        selectionStart = offset;
        selectionEnd = offset;
        cursorOffset = offset;
        handlesVisible = false;
    }

    void activateSelection(long offset) {
        selectionStart = offset;
        selectionEnd = offset;
        cursorOffset = offset;
        handlesVisible = true;
    }

    boolean moveStartHandle(long offset, long maxOffset) {
        if (!handlesVisible) {
            return false;
        }
        long clamped = clamp(offset, 0, maxOffset);
        selectionStart = clamped;
        boolean crossed = selectionStart > selectionEnd;
        if (crossed) {
            long tmp = selectionStart;
            selectionStart = selectionEnd;
            selectionEnd = tmp;
        }
        cursorOffset = selectionStart;
        return crossed;
    }

    boolean moveEndHandle(long offset, long maxOffset) {
        if (!handlesVisible) {
            return false;
        }
        long clamped = clamp(offset, 0, maxOffset);
        selectionEnd = clamped;
        boolean crossed = selectionEnd < selectionStart;
        if (crossed) {
            long tmp = selectionStart;
            selectionStart = selectionEnd;
            selectionEnd = tmp;
        }
        cursorOffset = selectionEnd;
        return crossed;
    }

    boolean hasSelection() {
        return selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd;
    }

    boolean isHandlesVisible() {
        return handlesVisible;
    }

    void dismissHandles() {
        handlesVisible = false;
    }

    long getSelectionStart() {
        return selectionStart;
    }

    long getSelectionEnd() {
        return selectionEnd;
    }

    long getCursorOffset() {
        return cursorOffset;
    }

    boolean isSelected(long offset) {
        return selectionStart >= 0 && selectionEnd >= 0 && offset >= selectionStart && offset <= selectionEnd;
    }

    boolean isCaretVisible() {
        return cursorOffset >= 0 && !handlesVisible;
    }

    void clear() {
        selectionStart = -1;
        selectionEnd = -1;
        cursorOffset = -1;
        handlesVisible = false;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
