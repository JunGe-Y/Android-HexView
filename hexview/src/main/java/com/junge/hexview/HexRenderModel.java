package com.junge.hexview;
import java.util.ArrayList;

import java.util.Collections;

import java.util.List;



final class HexRenderModel {



    final int startOffset;

    final int totalSize;

    final int bytesPerRow;

    final List<HexRow> rows;



    HexRenderModel(int startOffset, int totalSize, int bytesPerRow, List<HexRow> rows) {

        this.startOffset = startOffset;

        this.totalSize = totalSize;

        this.bytesPerRow = bytesPerRow;

        this.rows = Collections.unmodifiableList(rows);

    }



    static HexRenderModel empty() {

        return new HexRenderModel(0, 0, 16, new ArrayList<>());

    }



    static HexRenderModel from(int startOffset, byte[] bytes, int totalSize, int bytesPerRow) {

        List<HexRow> rows = new ArrayList<>();

        int rowCount = (bytes.length + bytesPerRow - 1) / bytesPerRow;

        for (int i = 0; i < rowCount; i++) {

            int rowStart = i * bytesPerRow;

            int rowLength = Math.min(bytesPerRow, bytes.length - rowStart);

            rows.add(new HexRow(startOffset + rowStart, bytes, rowStart, rowLength));

        }

        return new HexRenderModel(startOffset, totalSize, bytesPerRow, rows);

    }



    int getWindowEndOffset() {

        if (rows.isEmpty()) {

            return startOffset;

        }

        HexRow last = rows.get(rows.size() - 1);

        return last.offset + last.length;

    }

}


