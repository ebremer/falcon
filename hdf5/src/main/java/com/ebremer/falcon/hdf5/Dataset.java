package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.message.DatatypeMessage;
import com.ebremer.falcon.hdf5.message.DataspaceMessage;

/**
 * A dataset (or, for now, any non-group leaf) in the HDF5 hierarchy.
 *
 * <p>Stage H2 exposes the {@link #datatype()} and {@link #dataspace()}. Reading element data follows
 * in stages H3–H4.
 */
public final class Dataset extends Hdf5Object {

    private Datatype datatype;
    private Dataspace dataspace;

    private Dataset(FileContext ctx, String name, String path, long objectHeaderAddress) {
        super(ctx, name, path, objectHeaderAddress);
    }

    static Dataset child(FileContext ctx, String name, String parentPath, long objectHeaderAddress) {
        return new Dataset(ctx, name, childPath(parentPath, name), objectHeaderAddress);
    }

    @Override
    public boolean isGroup() {
        return false;
    }

    /** This dataset's element datatype. */
    public Datatype datatype() {
        if (datatype == null) {
            HeaderMessage message = header().find(MessageType.DATATYPE);
            if (message == null) {
                throw new HdfFormatException("dataset " + path() + " has no datatype message");
            }
            datatype = DatatypeMessage.parse(ctx.buffer(), message.bodyOffset());
        }
        return datatype;
    }

    /** This dataset's shape. */
    public Dataspace dataspace() {
        if (dataspace == null) {
            HeaderMessage message = header().find(MessageType.DATASPACE);
            if (message == null) {
                throw new HdfFormatException("dataset " + path() + " has no dataspace message");
            }
            dataspace = DataspaceMessage.parse(ctx, message.bodyOffset());
        }
        return dataspace;
    }
}
