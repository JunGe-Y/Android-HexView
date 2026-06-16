package com.junge.hexview;



import android.content.Context;

import android.net.Uri;







import java.io.FileInputStream;

import java.io.IOException;

import java.nio.ByteBuffer;

import java.nio.channels.FileChannel;



final class UriHexSource implements HexSource, AutoCloseable {



    private final Context context;

    private final Uri uri;

    private final Object ioLock = new Object();



    private android.os.ParcelFileDescriptor parcelFileDescriptor;

    private FileInputStream fileInputStream;

    private FileChannel channel;

    private int cachedSize = -1;



    UriHexSource(Context context, Uri uri) {

        this.context = context.getApplicationContext();

        this.uri = uri;

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

            if (cachedSize <= 0 || offset >= cachedSize) {

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

                if (fileInputStream != null) {

                    fileInputStream.close();

                }

            } catch (IOException ignored) {

            }

            try {

                if (parcelFileDescriptor != null) {

                    parcelFileDescriptor.close();

                }

            } catch (IOException ignored) {

            }

            channel = null;

            fileInputStream = null;

            parcelFileDescriptor = null;

            cachedSize = -1;

        }

    }



    private void ensureOpen() throws IOException {

        if (parcelFileDescriptor != null) {

            return;

        }

        parcelFileDescriptor = context.getContentResolver().openFileDescriptor(uri, "r");

        if (parcelFileDescriptor == null) {

            throw new IOException("Unable to open uri: " + uri);

        }

        cachedSize = (int) Math.min(Integer.MAX_VALUE, parcelFileDescriptor.getStatSize());

        fileInputStream = new FileInputStream(parcelFileDescriptor.getFileDescriptor());

        channel = fileInputStream.getChannel();

    }

}


