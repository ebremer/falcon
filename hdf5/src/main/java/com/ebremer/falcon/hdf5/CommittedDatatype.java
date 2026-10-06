package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.SharedMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.message.DatatypeMessage;

/**
 * A committed (named) datatype: a {@link Datatype} stored as its own object in the file so that many
 * datasets and attributes can share one definition. Its object header carries a Datatype message (and
 * no dataspace or data). Datasets and attributes reference it through a {@link SharedMessage}; this
 * object exposes the definition directly, and can itself carry attributes.
 */
public final class CommittedDatatype extends Hdf5Object {

    private CommittedDatatype(FileContext ctx, String name, String path, long objectHeaderAddress) {
        super(ctx, name, path, objectHeaderAddress);
    }

    static CommittedDatatype child(FileContext ctx, String name, String parentPath, long objectHeaderAddress) {
        return new CommittedDatatype(ctx, name, childPath(parentPath, name), objectHeaderAddress);
    }

    @Override
    public boolean isGroup() {
        return false;
    }

    /**
     * The committed datatype definition.
     *
     * @return the datatype this object stores
     * @throws HdfFormatException if the object header has no datatype message
     */
    public Datatype datatype() {
        Datatype result = state.datatype;
        if (result == null) {
            HeaderMessage message = header().find(MessageType.DATATYPE);
            if (message == null) {
                throw new HdfFormatException("committed datatype " + path() + " has no datatype message");
            }
            result = DatatypeMessage.resolve(ctx, message.bodyOffset(), SharedMessage.isShared(message));
            state.datatype = result;
        }
        return result;
    }
}
