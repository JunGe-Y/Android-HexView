package com.junge.hexview;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.View.MeasureSpec;
import android.widget.PopupWindow;
import android.widget.OverScroller;
import android.widget.Toast;



import java.io.File;

import com.junge.hexview.R;
import java.util.Locale;

public class HexView extends View implements HexDataController.Callback {

    private static final int DEFAULT_BYTES_PER_ROW = 8;
    private static final int DEFAULT_WINDOW_BYTES = 10 * 1024;
    private static final long CARET_BLINK_MS = 500L;
    private static final int VIEWPORT_BUCKET_ROWS = 64;
    private static final int SCROLLBAR_GUTTER_DP = 12;
    private static final int SCROLLBAR_TOUCH_WIDTH_DP = 28;
    private static final int SCROLLBAR_THUMB_MIN_DP = 32;
    private static final int SCROLLER_COORDINATE_LIMIT = 1_000_000_000;
    private static final String[] HEX_STRINGS = new String[256];
    private static final String[] ASCII_STRINGS = new String[256];

    static {
        for (int i = 0; i < 256; i++) {
            HEX_STRINGS[i] = String.format(Locale.US, "%02X", i);
            int v = i;
            ASCII_STRINGS[i] = v >= 32 && v <= 126 ? String.valueOf((char) v) : ".";
        }
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caretPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollbarTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollbarThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private final VelocityTracker velocityTracker = VelocityTracker.obtain();
    private final int minimumVelocity;
    private final int maximumVelocity;

    private final HexDataController dataController;
    private final HexSelectionController selectionController = new HexSelectionController();

    private HexRenderModel model = HexRenderModel.empty();
    private int bytesPerRow = DEFAULT_BYTES_PER_ROW;
    private int offsetWidthChars = 4;
    private float rowHeight;
    private float offsetColumnWidth;
    private float hexColumnLeft;
    private float asciiColumnLeft;
    private float hexCellWidth;
    private float asciiCellWidth;
    private float columnGap;
    private double contentHeight;
    private float contentWidth;
    private float scrollbarGutter;
    private float scrollbarThumbTop;
    private float scrollbarThumbHeight;
    private float hexTextXOffset;
    private float asciiTextXOffset;
    private float textBaselineOffset;
    private int lastViewportBucketStart = -1;
    private int lastViewportBucketEnd = -1;
    private int cachedTotalByteSize = 0;
    private final char[] offsetChars = new char[16];
    private double maxScrollY;
    private double scrollYPosition;
    private boolean draggingStartHandle;
    private boolean draggingEndHandle;
    private boolean draggingScroll;
    private boolean draggingScrollbar;
    private boolean caretVisible = true;
    private PopupWindow copyPopup;

    private enum HandleHit {
        NONE, START, END
    }

    private final Runnable caretBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!selectionController.isCaretVisible()) {
                caretVisible = false;
                return;
            }
            caretVisible = !caretVisible;
            invalidate();
            postDelayed(this, CARET_BLINK_MS);
        }
    };

    public HexView(Context context) {
        this(context, null);
    }

    public HexView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HexView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setWillNotDraw(false);

        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        textPaint.setTextSize(sp(16));
        textPaint.setColor(Color.BLACK);

        selectionPaint.setColor(Color.parseColor("#D6E9FF"));
        caretPaint.setColor(Color.BLACK);
        caretPaint.setStrokeWidth(dp(2));
        linePaint.setColor(0xFFB0B0B0);
        linePaint.setStrokeWidth(dp(1));
        handlePaint.setColor(Color.parseColor("#1A73E8"));
        handlePaint.setStyle(Paint.Style.FILL);

        scrollbarTrackPaint.setColor(0x22000000);
        scrollbarThumbPaint.setColor(0x88000000);

        ViewConfiguration configuration = ViewConfiguration.get(context);
        minimumVelocity = configuration.getScaledMinimumFlingVelocity();
        maximumVelocity = configuration.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                scrollByInternal(distanceY);
                draggingScroll = true;
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                long offset = hitTest(e.getX(), e.getY());
                if (offset >= 0) {
                    if (selectionController.isHandlesVisible() && selectionController.isSelected(offset)) {
                        showOrUpdateCopyMenu(anchorHandleForOffset(offset));
                        draggingScroll = false;
                        invalidate();
                    } else {
                        selectionController.setCaret(offset);
                        draggingScroll = false;
                        restartCaretBlink();
                        ensureOffsetVisible(offset);
                        invalidate();
                    }
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                activateSelectionAt(e.getX(), e.getY());
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                activateSelectionAt(e.getX(), e.getY());
                return true;
            }
        });

        dataController = new HexDataController(new Handler(Looper.getMainLooper()), this, bytesPerRow, DEFAULT_WINDOW_BYTES);
    }

    @Override
    public void onDataChanged(HexRenderModel model) {
        int previousTotalSize = cachedTotalByteSize;
        cachedTotalByteSize = Math.max(model.totalSize, dataController.getSourceSize());
        int newOffsetWidth = Math.max(4, Integer.toHexString(Math.max(0, cachedTotalByteSize)).length());
        int newBytesPerRow = Math.max(1, model.bytesPerRow);
        boolean needsLayout = newOffsetWidth != offsetWidthChars
                || newBytesPerRow != bytesPerRow
                || cachedTotalByteSize != previousTotalSize;
        this.model = model;
        this.bytesPerRow = newBytesPerRow;
        this.offsetWidthChars = newOffsetWidth;
        if (needsLayout) {
            lastViewportBucketStart = -1;
            lastViewportBucketEnd = -1;
            requestLayout();
        } else {
            requestWindowForViewport();
            invalidate();
        }
    }

    public void setSource(HexSource source) {
        dataController.setSource(source);
    }

    public void setBytes(byte[] bytes) {
        dataController.setSource(new InMemoryHexSource(bytes));
    }

    public void setFile(File file) {
        dataController.setSource(new FileHexSource(file));
    }

    public void setUri(Uri uri) {
        dataController.setSource(new UriHexSource(getContext(), uri));
    }

    public void clear() {
        dataController.clear();
        selectionController.clear();
        cachedTotalByteSize = 0;
        scrollYPosition = 0;
        invalidate();
    }

    public void setBytesPerRow(int bytesPerRow) {
        this.bytesPerRow = Math.max(1, bytesPerRow);
        dataController.setBytesPerRow(this.bytesPerRow);
        requestLayout();
    }

    public long getSelectionStart() {
        return selectionController.getSelectionStart();
    }

    public long getSelectionEnd() {
        return selectionController.getSelectionEnd();
    }

    public long getCaretOffset() {
        return selectionController.getCursorOffset();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        updateMetrics(width);
        int desiredHeight = (int) Math.ceil(Math.min(contentHeight + getPaddingTop() + getPaddingBottom(), dp(320)));
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec));
        maxScrollY = Math.max(0, contentHeight - (getMeasuredHeight() - getPaddingTop() - getPaddingBottom()));
        scrollYPosition = Math.min(scrollYPosition, maxScrollY);
        requestWindowForViewport();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (model.rows.isEmpty() && cachedTotalByteSize <= 0) {
            return;
        }
        int save = canvas.save();
        drawRows(canvas);
        canvas.restoreToCount(save);
        drawSelectionHandles(canvas);
        drawScrollbar(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        velocityTracker.addMovement(event);

        if (draggingStartHandle || draggingEndHandle) {
            handleHandleDrag(event);
            return true;
        }

        if (draggingScrollbar) {
            handleScrollbarDrag(event);
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                draggingScroll = false;
                dismissCopyPopup();
                if (hitTestScrollbar(event.getX(), event.getY())) {
                    draggingScrollbar = true;
                    scrollToScrollbarY(event.getY());
                    return true;
                }
                HandleHit handleHit = hitTestHandle(event.getX(), event.getY());
                if (handleHit != HandleHit.NONE) {
                    draggingStartHandle = handleHit == HandleHit.START;
                    draggingEndHandle = handleHit == HandleHit.END;
                    removeCallbacks(caretBlinkRunnable);
                    return true;
                }
                gestureDetector.onTouchEvent(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                gestureDetector.onTouchEvent(event);
                return true;
            case MotionEvent.ACTION_UP:
                gestureDetector.onTouchEvent(event);
                if (draggingScrollbar) {
                    draggingScrollbar = false;
                    velocityTracker.clear();
                    return true;
                }
                if (draggingScroll) {
                    velocityTracker.computeCurrentVelocity(1000, maximumVelocity);
                    float velocityY = velocityTracker.getYVelocity();
                    if (Math.abs(velocityY) > minimumVelocity) {
                        fling(-velocityY);
                    }
                }
                draggingScroll = false;
                velocityTracker.clear();
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingStartHandle = false;
                draggingEndHandle = false;
                draggingScroll = false;
                draggingScrollbar = false;
                velocityTracker.clear();
                return true;
            default:
                gestureDetector.onTouchEvent(event);
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYPosition = scrollerToScrollY(scroller.getCurrY());
            requestWindowForViewport();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        dismissCopyPopup();
        removeCallbacks(caretBlinkRunnable);
        velocityTracker.recycle();
        dataController.shutdown();
    }

    private void drawRows(Canvas canvas) {
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingBottom = getPaddingBottom();
        int height = getHeight();
        float lineTop = paddingTop;
        float lineBottom = height - paddingBottom;

        int totalByteSize = cachedTotalByteSize;
        int firstVisibleRow = computeFirstVisibleRow();
        int lastVisibleRow = Math.min(getTotalRowCount() - 1, computeLastVisibleRow(height));
        for (int rowIndex = firstVisibleRow; rowIndex <= lastVisibleRow; rowIndex++) {
            float top = rowTopOnScreen(rowIndex);
            if (top + rowHeight < paddingTop || top > height - paddingBottom) {
                continue;
            }
            long rowOffset = (long) rowIndex * bytesPerRow;
            if (rowOffset >= totalByteSize) {
                break;
            }
            float baseline = top + this.textBaselineOffset;
            formatOffsetHex(rowOffset, offsetWidthChars, offsetChars);
            canvas.drawText(offsetChars, 0, offsetWidthChars, paddingLeft, baseline, textPaint);

            int modelRowIndex = getModelRowIndex(rowIndex);
            if (modelRowIndex < 0) {
                continue;
            }
            HexRow row = model.rows.get(modelRowIndex);

            for (int i = 0; i < bytesPerRow; i++) {
                long byteOffset = row.offset + i;
                boolean hasByte = i < row.length;
                float hexLeft = this.hexColumnLeft + i * hexCellWidth;
                float asciiLeft = this.asciiColumnLeft + i * asciiCellWidth;
                if (hasByte && selectionController.isSelected(byteOffset)) {
                    drawHexSelection(canvas, hexLeft, top, hexCellWidth, rowHeight);
                    drawAsciiSelection(canvas, asciiLeft, top, asciiCellWidth, rowHeight);
                }
                if (hasByte) {
                    int value = row.getByte(i) & 0xFF;
                    canvas.drawText(HEX_STRINGS[value], hexLeft + hexTextXOffset, baseline, textPaint);
                    canvas.drawText(ASCII_STRINGS[value], asciiLeft + asciiTextXOffset, baseline, textPaint);
                }
                if (selectionController.isCaretVisible() && caretVisible && selectionController.getCursorOffset() == byteOffset) {
                    drawCaret(canvas, hexLeft, asciiLeft, top, rowHeight);
                }
            }
        }

        if (lineBottom > lineTop) {
            canvas.drawLine(paddingLeft + offsetColumnWidth, lineTop, paddingLeft + offsetColumnWidth, lineBottom, linePaint);
            canvas.drawLine(hexColumnLeft - columnGap, lineTop, hexColumnLeft - columnGap, lineBottom, linePaint);
            canvas.drawLine(asciiColumnLeft - columnGap, lineTop, asciiColumnLeft - columnGap, lineBottom, linePaint);
        }
    }

    private int computeFirstVisibleRow() {
        if (rowHeight <= 0) {
            return 0;
        }
        return Math.max(0, (int) (scrollYPosition / rowHeight));
    }

    private int computeLastVisibleRow(int viewportHeight) {
        if (rowHeight <= 0) {
            return 0;
        }
        return (int) ((scrollYPosition + viewportHeight) / rowHeight) + 1;
    }

    private float rowTopOnScreen(int rowIndex) {
        return (float) ((double) rowIndex * rowHeight - scrollYPosition + getPaddingTop());
    }

    private void drawHexSelection(Canvas canvas, float left, float top, float width, float height) {
        rect.set(left - dp(1), top, left + width, top + height);
        canvas.drawRect(rect, selectionPaint);
    }

    private void drawAsciiSelection(Canvas canvas, float left, float top, float width, float height) {
        rect.set(left, top, left + width + dp(1), top + height);
        canvas.drawRect(rect, selectionPaint);
    }

    private void drawCaret(Canvas canvas, float hexLeft, float asciiLeft, float top, float height) {
        float caretX = hexLeft + hexCellWidth * 0.78f;
        if (caretX > asciiLeft - dp(3)) {
            caretX = asciiLeft - dp(3);
        }
        canvas.drawLine(caretX, top + dp(4), caretX, top + height - dp(4), caretPaint);
    }

    private void drawSelectionHandles(Canvas canvas) {
        if (!selectionController.isHandlesVisible()) {
            return;
        }
        long start = selectionController.getSelectionStart();
        long end = selectionController.getSelectionEnd();
        if (start < 0 || end < 0) {
            return;
        }
        drawStartHandle(canvas, offsetToHandleScreenX(start, true), offsetToHandleScreenY(start));
        drawEndHandle(canvas, offsetToHandleScreenX(end, false), offsetToHandleScreenY(end));
    }

    private void drawStartHandle(Canvas canvas, float cornerX, float cornerY) {
        float radius = getHandleRadius();
        float inset = getHandleInset();
        canvas.drawCircle(cornerX + inset, cornerY - inset, radius, handlePaint);
    }

    private void drawEndHandle(Canvas canvas, float cornerX, float cornerY) {
        float radius = getHandleRadius();
        float inset = getHandleInset();
        canvas.drawCircle(cornerX - inset, cornerY - inset, radius, handlePaint);
    }

    private float getHandleRadius() {
        return dp(7);
    }

    private float getHandleInset() {
        return getHandleRadius() * 0.45f;
    }

    private float getHandleCenterX(long offset, boolean startHandle) {
        float cornerX = offsetToHandleScreenX(offset, startHandle);
        float inset = getHandleInset();
        return startHandle ? cornerX + inset : cornerX - inset;
    }

    private float getHandleCenterY(long offset) {
        return offsetToHandleScreenY(offset) - getHandleInset();
    }

    private void handleHandleDrag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float y = event.getY();
                int edge = dp(36);
                if (y < edge || y > getHeight() - edge) {
                    autoScrollHandleDrag(y);
                }
                long offset = hitTest(event.getX(), resolveHandleDragHitY(y, edge));
                if (offset >= 0) {
                    long maxOffset = getMaxOffset();
                    if (draggingStartHandle) {
                        selectionController.moveStartHandle(offset, maxOffset);
                    } else if (draggingEndHandle) {
                        selectionController.moveEndHandle(offset, maxOffset);
                    }
                    ensureOffsetVisible(offset);
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                HandleHit releasedHandle = draggingStartHandle ? HandleHit.START : HandleHit.END;
                draggingStartHandle = false;
                draggingEndHandle = false;
                velocityTracker.clear();
                if (selectionController.isHandlesVisible()) {
                    showOrUpdateCopyMenu(releasedHandle);
                }
                invalidate();
                break;
            default:
                break;
        }
    }

    private HandleHit hitTestHandle(float screenX, float screenY) {
        if (!selectionController.isHandlesVisible()) {
            return HandleHit.NONE;
        }
        long start = selectionController.getSelectionStart();
        long end = selectionController.getSelectionEnd();
        if (start < 0 || end < 0) {
            return HandleHit.NONE;
        }
        float touchRadius = getHandleRadius() + dp(6);
        float startCx = getHandleCenterX(start, true);
        float startCy = getHandleCenterY(start);
        float endCx = getHandleCenterX(end, false);
        float endCy = getHandleCenterY(end);
        if (distance(screenX, screenY, startCx, startCy) <= touchRadius) {
            return HandleHit.START;
        }
        if (distance(screenX, screenY, endCx, endCy) <= touchRadius) {
            return HandleHit.END;
        }
        return HandleHit.NONE;
    }

    private float offsetToHandleScreenX(long offset, boolean startHandle) {
        int indexInRow = (int) (offset % bytesPerRow);
        float cellLeft = hexColumnLeft + indexInRow * hexCellWidth;
        float anchor = startHandle ? cellLeft : cellLeft + hexCellWidth;
        return anchor;
    }

    private float offsetToHandleScreenY(long offset) {
        int rowIndex = offsetToAbsoluteRowIndex(offset);
        return rowTopOnScreen(rowIndex) + rowHeight;
    }

    private float resolveHandleDragHitY(float y, int edge) {
        if (y >= edge && y <= getHeight() - edge) {
            return y;
        }
        if (y < edge && scrollYPosition <= 0) {
            float firstRowTop = rowTopOnScreen(0);
            float firstRowBottom = firstRowTop + rowHeight;
            if (firstRowBottom > getPaddingTop()) {
                return Math.max(getPaddingTop(), Math.min(y, firstRowBottom - 1f));
            }
        }
        if (y > getHeight() - edge && scrollYPosition >= maxScrollY) {
            int lastRow = getTotalRowCount() - 1;
            if (lastRow >= 0) {
                float lastRowTop = rowTopOnScreen(lastRow);
                float contentBottom = getHeight() - getPaddingBottom();
                if (lastRowTop < contentBottom) {
                    return Math.max(lastRowTop, Math.min(y, contentBottom));
                }
            }
        }
        return clamp(y, edge, getHeight() - edge);
    }

    private void autoScrollHandleDrag(float screenY) {
        int edge = dp(36);
        if (screenY < edge && scrollYPosition > 0) {
            scrollByInternal(-rowHeight);
        } else if (screenY > getHeight() - edge && scrollYPosition < maxScrollY) {
            scrollByInternal(rowHeight);
        }
    }

    private long getMaxOffset() {
        return Math.max(0, model.totalSize - 1);
    }

    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.hypot(dx, dy);
    }

    private void updateMetrics(int width) {
        int paddingLeft = getPaddingLeft();
        int paddingRight = getPaddingRight();
        int safeBytesPerRow = Math.max(1, bytesPerRow);
        float offsetTextWidth = textPaint.measureText(String.format(Locale.US, "%0" + offsetWidthChars + "X", Math.max(0, model.totalSize)));
        float asciiSampleWidth = textPaint.measureText("M");
        float hexTextWidth = textPaint.measureText("FF");
        columnGap = dp(6);
        rowHeight = Math.max(textPaint.getTextSize() * 1.55f, dp(24));
        offsetColumnWidth = offsetTextWidth + columnGap;
        hexColumnLeft = paddingLeft + offsetColumnWidth + columnGap;

        scrollbarGutter = dp(SCROLLBAR_GUTTER_DP);
        float totalRowWidth = Math.max(0, width - paddingLeft - paddingRight - scrollbarGutter);
        float fixedWidth = offsetColumnWidth + columnGap + columnGap;
        float availableCellsWidth = Math.max(0, totalRowWidth - fixedWidth);
        float compactHexCellWidth = hexTextWidth + dp(4);
        float compactAsciiCellWidth = asciiSampleWidth;
        float compactCellsWidth = compactHexCellWidth * safeBytesPerRow + compactAsciiCellWidth * safeBytesPerRow;
        float extraPerHexCell = compactCellsWidth < availableCellsWidth
                ? (availableCellsWidth - compactCellsWidth) / safeBytesPerRow
                : 0f;
        hexCellWidth = Math.max(hexTextWidth + dp(2), compactHexCellWidth + extraPerHexCell);
        asciiCellWidth = compactAsciiCellWidth;
        float hexWidth = hexCellWidth * safeBytesPerRow;
        asciiColumnLeft = hexColumnLeft + hexWidth + columnGap;
        contentHeight = (double) getTotalRowCount() * rowHeight;
        contentWidth = asciiColumnLeft + asciiCellWidth * safeBytesPerRow + paddingRight;

        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        textBaselineOffset = (rowHeight - fontMetrics.descent - fontMetrics.ascent) * 0.5f;
        hexTextXOffset = (hexCellWidth - hexTextWidth) * 0.5f;
        asciiTextXOffset = (asciiCellWidth - asciiSampleWidth) * 0.5f;
    }

    private long hitTest(float x, float y) {
        if (x >= getWidth() - scrollbarGutter) {
            return -1;
        }
        int rowIndex = (int) ((y + scrollYPosition - getPaddingTop()) / rowHeight);
        if (rowIndex < 0) {
            if (scrollYPosition <= 0) {
                rowIndex = 0;
            } else {
                return -1;
            }
        }
        if (rowIndex >= getTotalRowCount()) {
            return -1;
        }
        int modelRowIndex = getModelRowIndex(rowIndex);
        if (modelRowIndex < 0) {
            return -1;
        }
        HexRow row = model.rows.get(modelRowIndex);
        float localX = x;
        int hexIndex = (int) ((localX - hexColumnLeft) / Math.max(1f, hexCellWidth));
        int asciiIndex = (int) ((localX - asciiColumnLeft) / Math.max(1f, asciiCellWidth));
        int index = localX < asciiColumnLeft ? hexIndex : asciiIndex;
        if (index < 0) index = 0;
        if (index >= row.length) index = row.length - 1;
        return row.offset + index;
    }

    private void scrollByInternal(float dy) {
        scrollYPosition = clamp(scrollYPosition + dy, 0, maxScrollY);
        requestWindowForViewport();
        if (copyPopup != null && copyPopup.isShowing()) {
            dismissCopyPopup();
        }
        postInvalidateOnAnimation();
    }

    private void restartCaretBlink() {
        removeCallbacks(caretBlinkRunnable);
        caretVisible = true;
        if (selectionController.isCaretVisible()) {
            postDelayed(caretBlinkRunnable, CARET_BLINK_MS);
        }
    }

    private void fling(float velocityY) {
        int scrollerMaxY = getScrollerMaxY();
        int scrollerStartY = scrollYToScroller(scrollYPosition);
        int scrollerVelocityY = scrollVelocityToScroller(velocityY);
        scroller.fling(0, scrollerStartY, 0, scrollerVelocityY, 0, 0, 0, scrollerMaxY);
        postInvalidateOnAnimation();
    }

    private int getScrollerMaxY() {
        if (maxScrollY <= 0) {
            return 0;
        }
        return (int) Math.min(SCROLLER_COORDINATE_LIMIT, Math.ceil(maxScrollY));
    }

    private int scrollYToScroller(double value) {
        int scrollerMaxY = getScrollerMaxY();
        if (scrollerMaxY <= 0 || maxScrollY <= 0) {
            return 0;
        }
        double clampedValue = clamp(value, 0, maxScrollY);
        return (int) Math.round(clampedValue / maxScrollY * scrollerMaxY);
    }

    private double scrollerToScrollY(int value) {
        int scrollerMaxY = getScrollerMaxY();
        if (scrollerMaxY <= 0 || maxScrollY <= 0) {
            return 0;
        }
        double ratio = clamp((double) value / scrollerMaxY, 0, 1);
        return ratio * maxScrollY;
    }

    private int scrollVelocityToScroller(float velocityY) {
        int scrollerMaxY = getScrollerMaxY();
        if (scrollerMaxY <= 0 || maxScrollY <= 0) {
            return 0;
        }
        double scaledVelocity = velocityY / maxScrollY * scrollerMaxY;
        return (int) Math.round(clamp(scaledVelocity, -maximumVelocity, maximumVelocity));
    }

    private void ensureOffsetVisible(long offset) {
        int rowIndex = offsetToAbsoluteRowIndex(offset);
        if (rowIndex < 0) {
            return;
        }
        double rowTop = (double) rowIndex * rowHeight;
        double rowBottom = rowTop + rowHeight;
        double visibleTop = scrollYPosition;
        double visibleBottom = scrollYPosition + getHeight() - getPaddingTop() - getPaddingBottom();
        if (rowTop < visibleTop) {
            scrollYPosition = clamp(rowTop, 0, maxScrollY);
        } else if (rowBottom > visibleBottom) {
            scrollYPosition = clamp(rowBottom - (getHeight() - getPaddingTop() - getPaddingBottom()), 0, maxScrollY);
        }
        requestWindowForViewport();
    }

    private int offsetToAbsoluteRowIndex(long offset) {
        if (offset < 0 || bytesPerRow <= 0) {
            return -1;
        }
        return (int) (offset / bytesPerRow);
    }

    private void requestWindowForViewport() {
        if (rowHeight <= 0 || bytesPerRow <= 0) {
            return;
        }
        if (cachedTotalByteSize <= 0) {
            return;
        }
        int totalByteSize = cachedTotalByteSize;
        int firstVisibleRow = computeFirstVisibleRow();
        int lastVisibleRow = Math.max(firstVisibleRow, computeLastVisibleRow(getHeight()));
        int visibleStartOffset = (int) Math.min((long) firstVisibleRow * bytesPerRow, totalByteSize);
        int visibleEndOffset = (int) Math.min((long) (lastVisibleRow + 1L) * bytesPerRow, totalByteSize);
        int bucketStart = firstVisibleRow / VIEWPORT_BUCKET_ROWS;
        int bucketEnd = lastVisibleRow / VIEWPORT_BUCKET_ROWS;
        boolean bucketUnchanged = bucketStart == lastViewportBucketStart && bucketEnd == lastViewportBucketEnd;
        if (bucketUnchanged && modelCoversRange(visibleStartOffset, visibleEndOffset)) {
            return;
        }
        lastViewportBucketStart = bucketStart;
        lastViewportBucketEnd = bucketEnd;
        dataController.onViewportChanged(visibleStartOffset, visibleEndOffset);
    }

    private boolean modelCoversRange(int startOffset, int endOffset) {
        if (model.rows.isEmpty() || endOffset <= startOffset) {
            return false;
        }
        return model.startOffset <= startOffset && model.getWindowEndOffset() >= endOffset;
    }

    private int getTotalRowCount() {
        int totalByteSize = cachedTotalByteSize;
        if (totalByteSize <= 0 || bytesPerRow <= 0) {
            return 0;
        }
        return (totalByteSize + bytesPerRow - 1) / bytesPerRow;
    }

    private int getModelRowIndex(int absoluteRowIndex) {
        if (model.rows.isEmpty() || absoluteRowIndex < 0) {
            return -1;
        }
        long rowOffset = (long) absoluteRowIndex * bytesPerRow;
        if (rowOffset < model.startOffset) {
            return -1;
        }
        int index = (int) ((rowOffset - model.startOffset) / bytesPerRow);
        if (index < 0 || index >= model.rows.size()) {
            return -1;
        }
        HexRow row = model.rows.get(index);
        return row.offset == rowOffset ? index : -1;
    }

    private static void formatOffsetHex(long value, int width, char[] out) {
        for (int i = width - 1; i >= 0; i--) {
            int nibble = (int) (value & 0xF);
            out[i] = (char) (nibble < 10 ? '0' + nibble : 'A' + nibble - 10);
            value >>>= 4;
        }
    }

    private void activateSelectionAt(float x, float y) {
        if (hitTestScrollbar(x, y)) {
            return;
        }
        long offset = hitTest(x, y);
        if (offset >= 0) {
            selectionController.activateSelection(offset);
            caretVisible = false;
            removeCallbacks(caretBlinkRunnable);
            ensureOffsetVisible(offset);
            invalidate();
        }
    }

    private float getViewportHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    private boolean isScrollable() {
        return maxScrollY > 0;
    }

    private void updateScrollbarMetrics() {
        if (!isScrollable()) {
            scrollbarThumbHeight = 0;
            scrollbarThumbTop = getPaddingTop();
            return;
        }
        float trackHeight = getViewportHeight();
        float ratio = (float) (trackHeight / Math.max(1.0, contentHeight));
        scrollbarThumbHeight = Math.max(dp(SCROLLBAR_THUMB_MIN_DP), trackHeight * ratio);
        float thumbTravel = Math.max(0f, trackHeight - scrollbarThumbHeight);
        scrollbarThumbTop = getPaddingTop() + (float) ((scrollYPosition / maxScrollY) * thumbTravel);
    }

    private void drawScrollbar(Canvas canvas) {
        if (!isScrollable()) {
            return;
        }
        updateScrollbarMetrics();
        float thumbWidth = dp(4);
        float trackRight = getWidth();
        float trackLeft = trackRight - thumbWidth;
        float trackTop = getPaddingTop();
        float trackBottom = getHeight() - getPaddingBottom();

        rect.set(trackLeft, trackTop, trackRight, trackBottom);
        canvas.drawRoundRect(rect, thumbWidth * 0.5f, thumbWidth * 0.5f, scrollbarTrackPaint);

        rect.set(trackLeft, scrollbarThumbTop, trackRight, scrollbarThumbTop + scrollbarThumbHeight);
        canvas.drawRoundRect(rect, thumbWidth * 0.5f, thumbWidth * 0.5f, scrollbarThumbPaint);
    }

    private boolean hitTestScrollbar(float x, float y) {
        if (!isScrollable()) {
            return false;
        }
        int touchWidth = dp(SCROLLBAR_TOUCH_WIDTH_DP);
        return x >= getWidth() - touchWidth
                && x <= getWidth()
                && y >= getPaddingTop()
                && y <= getHeight() - getPaddingBottom();
    }

    private void handleScrollbarDrag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                scrollToScrollbarY(event.getY());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingScrollbar = false;
                break;
            default:
                break;
        }
    }

    private void scrollToScrollbarY(float y) {
        updateScrollbarMetrics();
        float trackTop = getPaddingTop();
        float trackHeight = getViewportHeight();
        float thumbTravel = Math.max(0f, trackHeight - scrollbarThumbHeight);
        float relativeY = y - trackTop - scrollbarThumbHeight * 0.5f;
        float ratio = thumbTravel <= 0f ? 0f : clamp(relativeY / thumbTravel, 0f, 1f);
        scrollYPosition = ratio * maxScrollY;
        requestWindowForViewport();
        postInvalidateOnAnimation();
    }

    private HandleHit anchorHandleForOffset(long offset) {
        long start = selectionController.getSelectionStart();
        long end = selectionController.getSelectionEnd();
        if (start < 0 || end < 0) {
            return HandleHit.END;
        }
        long rangeStart = Math.min(start, end);
        long rangeEnd = Math.max(start, end);
        return offset <= (rangeStart + rangeEnd) / 2 ? HandleHit.START : HandleHit.END;
    }

    private void showOrUpdateCopyMenu(HandleHit anchorHandle) {
        if (!selectionController.isHandlesVisible()) {
            return;
        }
        long start = selectionController.getSelectionStart();
        long end = selectionController.getSelectionEnd();
        if (start < 0 || end < 0) {
            return;
        }
        long rangeStart = Math.min(start, end);
        long rangeEnd = Math.max(start, end);
        if (rangeEnd < rangeStart) {
            return;
        }

        ensureCopyPopup();

        View toolbar = copyPopup.getContentView();
        toolbar.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int menuWidth = toolbar.getMeasuredWidth();
        int menuHeight = toolbar.getMeasuredHeight();

        long anchorOffset = anchorHandle == HandleHit.START ? rangeStart : rangeEnd;
        int rowIndex = offsetToAbsoluteRowIndex(anchorOffset);
        float rowTop = rowTopOnScreen(rowIndex);
        float rowBottom = rowTop + rowHeight;
        float handleCenterX = getHandleCenterX(anchorOffset, anchorHandle == HandleHit.START);

        int margin = dp(8);
        int[] viewLocation = new int[2];
        getLocationOnScreen(viewLocation);

        int screenX = viewLocation[0] + Math.round(handleCenterX - menuWidth * 0.5f);
        int screenY;
        if (anchorHandle == HandleHit.START) {
            screenY = viewLocation[1] + Math.round(rowTop - menuHeight - margin);
            int minY = viewLocation[1] + getPaddingTop();
            if (screenY < minY) {
                screenY = minY;
            }
        } else {
            screenY = viewLocation[1] + Math.round(rowBottom + margin);
            int maxY = viewLocation[1] + getHeight() - getPaddingBottom() - menuHeight;
            if (screenY > maxY) {
                screenY = Math.max(viewLocation[1] + getPaddingTop(), maxY);
            }
        }

        int minX = viewLocation[0] + getPaddingLeft();
        int maxX = viewLocation[0] + getWidth() - getPaddingRight() - menuWidth;
        screenX = Math.max(minX, Math.min(screenX, maxX));

        if (copyPopup.isShowing()) {
            copyPopup.update(screenX, screenY, -1, -1);
        } else {
            copyPopup.showAtLocation(this, Gravity.NO_GRAVITY, screenX, screenY);
        }
    }

    private void ensureCopyPopup() {
        if (copyPopup != null) {
            return;
        }
        View toolbar = LayoutInflater.from(getContext()).inflate(R.layout.hex_selection_toolbar, null);
        copyPopup = new PopupWindow(
                toolbar,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
        copyPopup.setOutsideTouchable(true);
        copyPopup.setFocusable(false);
        copyPopup.setElevation(dp(8));
        copyPopup.setOnDismissListener(() -> copyPopup = null);
        toolbar.findViewById(R.id.copyHexAction).setOnClickListener(v -> {
            copyCurrentSelection(true);
            dismissCopyPopup();
        });
        toolbar.findViewById(R.id.copyStringAction).setOnClickListener(v -> {
            copyCurrentSelection(false);
            dismissCopyPopup();
        });
    }

    private void copyCurrentSelection(boolean asHex) {
        long start = selectionController.getSelectionStart();
        long end = selectionController.getSelectionEnd();
        if (start < 0 || end < 0) {
            return;
        }
        long rangeStart = Math.min(start, end);
        long rangeEnd = Math.max(start, end);
        int length = (int) (rangeEnd - rangeStart + 1);
        if (length <= 0) {
            return;
        }
        byte[] data = dataController.readRange((int) rangeStart, length);
        if (data.length == 0) {
            return;
        }
        if (asHex) {
            copyToClipboard(formatHexCopy(data));
        } else {
            copyToClipboard(formatStringCopy(data));
        }
    }

    private void dismissCopyPopup() {
        if (copyPopup != null) {
            copyPopup.dismiss();
            copyPopup = null;
        }
    }

    private static String formatHexCopy(byte[] data) {
        if (data.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(HEX_STRINGS[data[i] & 0xFF]);
        }
        return builder.toString();
    }

    private static String formatStringCopy(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length);
        for (byte value : data) {
            int v = value & 0xFF;
            builder.append(v >= 32 && v <= 126 ? (char) v : '.');
        }
        return builder.toString();
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText("hex", text));
        Toast.makeText(getContext(), R.string.hexview_copied, Toast.LENGTH_SHORT).show();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
