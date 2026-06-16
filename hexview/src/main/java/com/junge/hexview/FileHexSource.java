package com.junge.hexview;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class FileHexSource implements HexSource, AutoCloseable {
    private final String TAG ="FileHexSource";
    private final File file;
    private final Object ioLock = new Object();
    private RandomAccessFile randomAccessFile;
    private FileChannel channel;
    private int cachedSize = -1;

    FileHexSource(File file) {
        this.file = file;
    }



    @Override
    public int size() {
        synchronized (ioLock) {
            if (cachedSize >= 0) {
                return cachedSize;
            }
            try {
                ensureOpen();
            } catch (IOException e) {
                Log.e(TAG,"getFileSizeError",e);
                return 0;
            }

            return cachedSize;

        }

    }



    @Override
    public byte[] read(int offset, int length) {
        if (offset < 0 || length <= 0) {
            return new byte[0];
        }
        synchronized (ioLock) {
            try {
                ensureOpen();
            } catch (IOException e) {
                return new byte[0];
            }

            if (offset >= cachedSize) {
                return new byte[0];
            }
            int safeLength = Math.min(length, cachedSize - offset);
            try {
                ByteBuffer buffer = ByteBuffer.allocate(safeLength);
                int totalRead = 0;
                while (totalRead < safeLength) {
                    int read = channel.read(buffer, offset + totalRead);
                    if (read < 0) {
                        break;
                    }
                    totalRead += read;
                }
                if (totalRead == 0) {
                    return new byte[0];
                }
                byte[] bytes = new byte[totalRead];
                buffer.flip();
                buffer.get(bytes);
                return bytes;
            } catch (IOException e) {
                Log.e(TAG,"read File Error",e);
                return new byte[0];

            }

        }

    }



    @Override

    public void close() {

        synchronized (ioLock) {
            try {
                if (channel != null) {
                    channel.close();
                }

            } catch (IOException ignored) {
            }

            try {
                if (randomAccessFile != null) {
                    randomAccessFile.close();
                }

            } catch (IOException ignored) {
            }

            channel = null;

            randomAccessFile = null;

            cachedSize = -1;

        }

    }



    private void ensureOpen() throws IOException {
        if (randomAccessFile != null) {
            return;
        }
        randomAccessFile = new RandomAccessFile(file, "r");
        channel = randomAccessFile.getChannel();
        long length = file.length();
        cachedSize = length > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) length;
    }

}


