package com.junge.hexview;



final class HexRow {

    final int offset;

    final byte[] data;

    final int dataOffset;

    final int length;



    HexRow(int offset, byte[] data, int dataOffset, int length) {

        this.offset = offset;

        this.data = data;

        this.dataOffset = dataOffset;

        this.length = length;

    }



    byte getByte(int index) {

        return data[dataOffset + index];

    }

}


