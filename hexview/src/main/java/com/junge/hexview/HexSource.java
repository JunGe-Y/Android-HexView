package com.junge.hexview;

public interface HexSource {
    int size();
    byte[] read(int offset, int length);
}
