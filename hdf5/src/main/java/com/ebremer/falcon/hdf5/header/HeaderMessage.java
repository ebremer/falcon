package com.ebremer.falcon.hdf5.header;

import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * One parsed object-header message: its {@linkplain MessageType type}, flags, and a pointer to its
 * body bytes within the file buffer. The body is decoded lazily by the type-specific message classes.
 */
public final class HeaderMessage {

    private final int type;
    private final int flags;
    private final HdfBuffer buffer;
    private final long bodyOffset;
    private final int bodySize;

    HeaderMessage(int type, int flags, HdfBuffer buffer, long bodyOffset, int bodySize) {
        this.type = type;
        this.flags = flags;
        this.buffer = buffer;
        this.bodyOffset = bodyOffset;
        this.bodySize = bodySize;
    }

    public int type() {
        return type;
    }

    public int flags() {
        return flags;
    }

    /** Absolute offset of the message body within the file buffer. */
    public long bodyOffset() {
        return bodyOffset;
    }

    public int bodySize() {
        return bodySize;
    }

    /** A bounded view over just this message's body. */
    public HdfBuffer body() {
        return buffer.slice(bodyOffset, bodySize);
    }

    @Override
    public String toString() {
        return "HeaderMessage[type=" + type + ", flags=" + flags + ", bodyOffset=" + bodyOffset
                + ", bodySize=" + bodySize + "]";
    }
}
