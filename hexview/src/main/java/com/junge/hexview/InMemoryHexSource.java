package com.junge.hexview;



import java.util.Arrays;

final class InMemoryHexSource implements HexSource {

    private final byte[] bytes;

    InMemoryHexSource(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public int size() {
        return bytes.length;
    }

    @Override
    public byte[] read(int offset, int length) {
        int end = Math.min(bytes.length, offset + length);
        if (offset < 0 || offset >= end) {
            return new byte[0];
        }
        return Arrays.copyOfRange(bytes, offset, end);
    }
}
