package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.ebremer.falcon.hdf5.Attribute;
import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.Hdf5Object;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/** {@code falcon dump}: an array's values, or part of them, or an attribute's. */
@Parameters(commandDescription = "Print an array's values (all of them, or a --slice), or an attribute's")
final class DumpCommand implements Command {

    /** The most source elements one read fetches: a dump streams through an array in reads of this size. */
    static final long READ_ELEMENTS = 1 << 20;

    @Parameter(description = "<source> <path>")
    List<String> arguments = new ArrayList<>();

    @Parameter(names = {"-s", "--slice"}, order = 0, description = "The part to print, numpy's way: an index or a "
            + "start:stop:step range per dimension, such as 0,10:20,::4")
    String slice;

    @Parameter(names = {"-f", "--format"}, order = 1, description = "text, csv, or json")
    String format = "text";

    @Parameter(names = {"-a", "--attribute"}, order = 2, description = "Print this attribute of the group or array instead")
    String attribute;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        Command.requireArguments(arguments, attribute == null ? 2 : 1, 2,
                attribute == null ? "<source> <path>" : "<source> [<path>]");
        DumpPrinter.of(format, context.out, Slices.parse(null, new long[0])); // checks --format first
        String path = arguments.size() > 1 ? arguments.get(1) : "/";
        try (Sources.Source source = Sources.open(context, arguments.get(0))) {
            switch (source) {
                case Sources.Hdf5Source h -> dumpHdf5(context, h.file(), path);
                case Sources.ZarrSource z -> dumpZarr(context, z.store(), path);
            }
        }
        return 0;
    }

    private void dumpHdf5(Context context, Hdf5File file, String path) {
        Hdf5Object object = Hdf5Inspect.resolve(file, path);
        if (attribute != null) {
            Attribute a = object.attribute(attribute).orElseThrow(() ->
                    new NoSuchElementException(object.path() + " has no attribute '" + attribute + "'"));
            noSlice();
            Dataspace space = a.dataspace();
            long[] shape = space.kind() == Dataspace.Kind.NULL ? new long[] {0} : space.dimensions();
            Values values = space.kind() == Dataspace.Kind.NULL ? Values.longs(new long[0]) : Hdf5Values.of(a);
            print(context, Slices.parse(null, shape), (offset, count) -> values, shape);
            return;
        }
        if (!(object instanceof Dataset dataset)) {
            throw new UsageException(object.path() + " is a group: dump prints a dataset, or with --attribute an "
                    + "attribute");
        }
        Dataspace space = dataset.dataspace();
        if (space.kind() == Dataspace.Kind.NULL) {
            context.err.println("falcon dump: " + dataset.path() + " has a null dataspace: no elements");
            return;
        }
        long[] shape = space.dimensions();
        print(context, Slices.parse(slice, shape), (offset, count) -> shape.length == 0 ? Hdf5Values.of(dataset)
                : Hdf5Values.of(dataset.select(offset, count)), shape);
    }

    private void dumpZarr(Context context, Store store, String path) {
        ZarrNode node = ZarrInspect.resolve(store, path);
        if (attribute != null) {
            JsonValue value = node.attributes().find(attribute).orElseThrow(() ->
                    new NoSuchElementException(ZarrInspect.display(node) + " has no attribute '" + attribute + "'"));
            noSlice();
            context.out.println(format.equals("json") ? Json.writePretty(value) : value.toJson());
            return;
        }
        if (!(node instanceof ZarrArray array)) {
            throw new UsageException(ZarrInspect.display(node) + " is a group: dump prints an array, or with "
                    + "--attribute an attribute");
        }
        ZarrArray cached = array.withChunkCache(64L << 20);
        long[] shape = array.shape();
        print(context, Slices.parse(slice, shape),
                (offset, count) -> ZarrValues.of(cached.select(offset, count), array.dataType()), shape);
    }

    private void noSlice() {
        if (slice != null) {
            throw new UsageException("--slice selects an array's values, not an attribute's");
        }
    }

    /** Reads a box of the source array. */
    interface BoxReader {
        Values read(long[] offset, long[] count);
    }

    /**
     * Streams the selection through the printer in C order: the selection is cut into boxes of whole rows (or
     * pieces of one row), each read in one go, so memory stays bounded however large the array.
     */
    private void print(Context context, Slices slices, BoxReader reader, long[] shape) {
        DumpPrinter printer = DumpPrinter.of(format, context.out, slices);
        int rank = shape.length;
        if (rank == 0) {
            Values values = reader.read(new long[0], new long[0]);
            if (values.size() > 0) {
                printer.element(values, 0);
            }
            printer.end(values.size() == 0);
            return;
        }
        long[] count = slices.count();
        long[] step = slices.step();
        long total = 1;
        for (long n : count) {
            total *= n;
        }
        if (total == 0) {
            printer.end(true);
            return;
        }
        long[] block = blockShape(count, step);
        long[] grid = new long[rank];
        long blocks = 1;
        for (int d = 0; d < rank; d++) {
            grid[d] = (count[d] + block[d] - 1) / block[d];
            blocks *= grid[d];
        }
        long[] at = new long[rank];
        for (long b = 0; b < blocks; b++) {
            long rest = b;
            for (int d = rank - 1; d >= 0; d--) {
                at[d] = rest % grid[d];
                rest /= grid[d];
            }
            long[] offset = new long[rank];
            long[] extent = new long[rank];
            long[] sourceOffset = new long[rank];
            long[] sourceCount = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = at[d] * block[d];
                extent[d] = Math.min(block[d], count[d] - offset[d]);
                sourceOffset[d] = slices.start()[d] + offset[d] * step[d];
                sourceCount[d] = (extent[d] - 1) * step[d] + 1;
            }
            Values values = reader.read(sourceOffset, sourceCount);
            emit(printer, values, extent, step, sourceCount);
        }
        printer.end(false);
    }

    /** Hands a box's selected elements to the printer, in C order, skipping those between the steps. */
    private static void emit(DumpPrinter printer, Values values, long[] extent, long[] step, long[] sourceCount) {
        int rank = extent.length;
        long[] stride = new long[rank];
        long s = 1;
        for (int d = rank - 1; d >= 0; d--) {
            stride[d] = s;
            s *= sourceCount[d];
        }
        long[] local = new long[rank];
        long n = 1;
        for (long e : extent) {
            n *= e;
        }
        for (long k = 0; k < n; k++) {
            long flat = 0;
            for (int d = 0; d < rank; d++) {
                flat += local[d] * step[d] * stride[d];
            }
            printer.element(values, (int) flat);
            for (int d = rank - 1; d >= 0; d--) {
                if (++local[d] < extent[d]) {
                    break;
                }
                local[d] = 0;
            }
        }
    }

    /**
     * The selection's blocks: grown from the last dimension while the source box they read stays within
     * {@link #READ_ELEMENTS}, and stopping at the first dimension not taken whole, so that taking the blocks
     * in C order takes the elements in C order.
     */
    static long[] blockShape(long[] count, long[] step) {
        int rank = count.length;
        long[] block = new long[rank];
        java.util.Arrays.fill(block, 1);
        long inner = 1; // the source elements a block reads in the dimensions after d
        for (int d = rank - 1; d >= 0; d--) {
            long most = Math.max(1, READ_ELEMENTS / inner);           // source extent allowed in d
            long k = most >= (count[d] - 1) * step[d] + 1 ? count[d] : Math.max(1, (most - 1) / step[d] + 1);
            block[d] = Math.min(k, count[d]);
            if (block[d] < count[d]) {
                break;
            }
            inner *= (count[d] - 1) * step[d] + 1;
        }
        return block;
    }
}
