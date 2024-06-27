package io.micronaut.http.poja.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A wrapper around input stream that limits the maximum size to be read.
 */
public class LimitingInputStream extends InputStream {

    private long size;
    private final InputStream stream;
    private final long maxSize;

    public LimitingInputStream(InputStream stream, long maxSize) {
        this.maxSize = maxSize;
        this.stream = stream;
    }

    @Override
    public synchronized void mark(int readlimit) {
        stream.mark(readlimit);
    }

    @Override
    public int read() throws IOException {
        return stream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        synchronized(this) {
            if (size >= maxSize) {
                return -1;
            }
            int sizeRead = stream.read(b);
            size += sizeRead;
            return size > maxSize ? sizeRead + (int) (maxSize - size) : sizeRead;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        synchronized (this) {
            if (size >= maxSize) {
                return -1;
            }
            int sizeRead = stream.read(b, off, len);
            size += sizeRead + off;
            return size > maxSize ? sizeRead + (int) (maxSize - size) : sizeRead;
        }
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        return stream.readNBytes((int) (maxSize - size));
    }

    @Override
    public byte[] readNBytes(int len) throws IOException {
        return stream.readNBytes(len);
    }

    @Override
    public int readNBytes(byte[] b, int off, int len) throws IOException {
        return stream.readNBytes(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return stream.skip(n);
    }

    @Override
    public void skipNBytes(long n) throws IOException {
        stream.skipNBytes(n);
    }

    @Override
    public int available() throws IOException {
        return stream.available();
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    @Override
    public synchronized void reset() throws IOException {
        stream.reset();
    }

    @Override
    public boolean markSupported() {
        return stream.markSupported();
    }

    @Override
    public long transferTo(OutputStream out) throws IOException {
        return stream.transferTo(out);
    }

    @Override
    public int hashCode() {
        return stream.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return stream.equals(obj);
    }

    @Override
    public String toString() {
        return stream.toString();
    }
}
