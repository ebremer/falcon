package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.Hdf5Object;
import com.ebremer.falcon.ome.Downsampling;
import com.ebremer.falcon.ome.MultiscaleImage;
import com.ebremer.falcon.ome.MultiscaleImageWriter;
import com.ebremer.falcon.ome.OmeVersion;
import com.ebremer.falcon.ome.OmeZarr;
import com.ebremer.falcon.ome.PixelSource;
import com.ebremer.falcon.ome.metadata.Axis;
import com.ebremer.falcon.ome.metadata.Omero;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code falcon ome pyramid}: an OME-Zarr multiscale image from an array (an HDF5 dataset or a Zarr array), its
 * smaller levels built from it; or, with {@code --label}, a label image of an OME-Zarr image.
 */
@Parameters(commandDescription = "Write an OME-Zarr image from an array (an HDF5 dataset or a Zarr array), building "
        + "its smaller levels; or with --label, a label image of an existing OME-Zarr image")
final class OmePyramidCommand implements Command {

    @Parameter(description = "<input> [<path>] <output>")
    List<String> arguments = new ArrayList<>();

    @Parameter(names = "--ome-version", order = 0, description = "The OME-Zarr version to write: 0.4 (Zarr v2), 0.5, "
            + "or 0.6 (Zarr v3)")
    String version = OmeVersion.defaultVersion().id();

    @Parameter(names = "--axes", order = 1, description = "The axes' names, in the array's order: letters (tczyx) or "
            + "names joined by commas; t is time, c channel, z, y, and x space (default: the Zarr array's dimension "
            + "names, else yx, cyx, czyx, or tczyx by its rank)")
    String axes;

    @Parameter(names = "--pixel-size", order = 2, description = "The full-resolution pixel size: one number for each "
            + "axis, or for each space axis, joined by commas (default 1)")
    String pixelSize;

    @Parameter(names = "--unit", order = 3, description = "The space axes' unit (default micrometer, with "
            + "--pixel-size)")
    String unit;

    @Parameter(names = "--time-unit", order = 4, description = "The time axis's unit, such as second")
    String timeUnit;

    @Parameter(names = "--origin", order = 5, description = "The physical position of the first pixel's center, one "
            + "number for each axis (default 0)")
    String origin;

    @Parameter(names = "--levels", order = 6, description = "How many levels to write (default: until the "
            + "downsampled axes fit --smallest)")
    Integer levels;

    @Parameter(names = "--smallest", order = 7, description = "Add levels until every downsampled axis is at most "
            + "this long")
    long smallest = 256;

    @Parameter(names = "--method", order = 8, description = "How a smaller level's pixels are made: mean (images), "
            + "nearest, or mode (labels)")
    String method;

    @Parameter(names = "--downsample", order = 9, description = "The axes to downsample, by name (default: the last "
            + "two space axes)")
    String downsample;

    @Parameter(names = "--factor", order = 10, description = "How much each level shrinks along the downsampled axes")
    int factor = 2;

    @Parameter(names = "--chunks", order = 11, description = "The chunk shape, one extent for each axis (default 512 "
            + "along the downsampled axes, 1 along the others)")
    String chunks;

    @Parameter(names = "--shards", order = 12, description = "Store the chunks in shards of this shape (0.5 and 0.6)")
    String shards;

    @Parameter(names = {"-c", "--compression"}, order = 13, description = "none, gzip[:level], zstd[:level] "
            + "(the default), blosc[:cname[:clevel[:shuffle]]], or bz2[:level]")
    String compression = "zstd";

    @Parameter(names = "--name", order = 14, description = "The image's name (default: the array's)")
    String name;

    @Parameter(names = "--channel-names", order = 15, description = "The channels' names, joined by commas: written "
            + "as omero metadata")
    String channelNames;

    @Parameter(names = "--channel-colors", order = 16, description = "The channels' colors, six hexadecimal digits "
            + "each (such as FF0000), joined by commas: written as omero metadata")
    String channelColors;

    @Parameter(names = "--label", order = 17, description = "Write the input as a label image of this name, in the "
            + "OME-Zarr image at <output>, with as many levels as it has")
    String label;

    @Parameter(names = "--overwrite", order = 18, description = "Replace the output if it exists")
    boolean overwrite;

    @Parameter(names = {"-j", "--threads"}, order = 19, description = "How many blocks to write at once")
    int threads = ConvertCommand.defaultThreads();

    @Parameter(names = {"-q", "--quiet"}, order = 20, description = "Do not list each level written")
    boolean quiet;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        Command.requireArguments(arguments, 2, 3, "<input> [<path>] <output>");
        OmeVersion v = OmeVersion.of(version).filter(x -> !version.equals("0.6rc0")).orElseThrow(() ->
                new UsageException("--ome-version is 0.4, 0.5, or 0.6, not '" + version + "'"));
        if (threads < 1) {
            throw new UsageException("--threads must be at least 1");
        }
        Compression codec = Compression.parse(compression);
        if (codec.kind() == Compression.Kind.LZF || codec.kind() == Compression.Kind.LZ4) {
            throw new UsageException("OME-Zarr levels are compressed with none, gzip, zstd, blosc, or bz2");
        }
        String input = arguments.getFirst();
        String path = arguments.size() == 3 ? arguments.get(1) : "/";
        String output = arguments.getLast();
        if (label != null && output.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new UsageException("a label image cannot be added to a ZIP archive: write the image and its "
                    + "labels to a directory, and zip that");
        }
        try (Sources.Source source = Sources.open(context, input);
             // a label image is added to the image there: the store itself is never replaced then
             Sources.ZarrTarget target = Sources.zarrTarget(context, output, overwrite && label == null)) {
            Input in = input(source, path);
            // a label image takes its image's version and axes
            MultiscaleImage of = label == null ? null : OmeZarr.open(target.store()).asImage();
            OmeVersion written = of == null ? v : of.version();
            List<Axis> axisList = of == null ? axes(in) : of.axes();
            if (of != null && axisList.size() != in.pixels().shape().length) {
                throw new UsageException("the labels have " + in.pixels().shape().length + " dimensions; the image has "
                        + axisList.size());
            }
            MultiscaleImageWriter.Builder b;
            try {
                b = MultiscaleImageWriter.builder(written, axisList);
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage() + " (axes " + String.join(", ", axisList.stream()
                        .map(Axis::name).toList()) + "; choose them with --axes)");
            }
            configure(b, axisList, in, written);
            List<long[]> levels = new ArrayList<>();
            b.progress(shape -> {
                if (!quiet) {
                    synchronized (levels) {
                        context.out.println("  level " + levels.size() + ": " + Describe.shape(shape));
                        levels.add(shape);
                    }
                }
            });
            MultiscaleImageWriter writer = b.build();
            MultiscaleImage image;
            try {
                if (of != null) {
                    if (overwrite && of.labelNames().contains(label)) {
                        of.group().group("labels").delete(label);
                    }
                    image = writer.writeLabel(of, label, in.pixels());
                } else {
                    image = writer.write(target.store(), in.pixels(), overwrite);
                }
            } catch (IllegalArgumentException e) {
                String m = String.valueOf(e.getMessage());
                throw overwrite || !(m.contains("exist") || m.contains("already") || m.contains("taken")) ? e
                        : new IllegalArgumentException(m + " (pass --overwrite to replace it)", e);
            }
            context.out.println("Wrote OME-Zarr " + written + (label != null ? " label image '" + label + "'" : " image")
                    + (image.name() == null || label != null ? "" : " '" + image.name() + "'") + ", "
                    + image.levelCount() + (image.levelCount() == 1 ? " level" : " levels") + ", to " + output);
        }
        return 0;
    }

    private void configure(MultiscaleImageWriter.Builder b, List<Axis> axisList, Input in, OmeVersion v) {
        int n = axisList.size();
        b.threads(threads).smallest(smallest);
        try {
            if (pixelSize != null) {
                double[] given = numbers(pixelSize, "--pixel-size");
                long spaces = axisList.stream().filter(Axis::isSpace).count();
                if (given.length == n) {
                    b.pixelSize(given);
                } else if (given.length == spaces) {
                    double[] all = new double[n];
                    int k = 0;
                    for (int d = 0; d < n; d++) {
                        all[d] = axisList.get(d).isSpace() ? given[k++] : 1;
                    }
                    b.pixelSize(all);
                } else {
                    throw new UsageException("--pixel-size gives " + given.length + " sizes for " + n + " axes ("
                            + spaces + " of them space)");
                }
            }
            if (origin != null) {
                b.origin(numbers(origin, "--origin"));
            }
            if (levels != null) {
                b.levels(levels);
            }
            if (method != null) {
                b.method(Downsampling.of(method));
            }
            if (downsample != null) {
                b.downsample(names(downsample));
            }
            b.factor(factor);
            if (chunks != null) {
                b.chunks(longs(chunks, "--chunks"));
            }
            if (shards != null) {
                b.shards(longs(shards, "--shards"));
            }
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        Compression codec = Compression.parse(compression);
        b.codec(spec -> codec.applyTo(spec, v.zarrFormat()));
        b.name(name != null ? name : in.name());
        if (channelNames != null || channelColors != null) {
            b.omero(omero(axisList, in));
        }
    }

    /** The omero metadata: a name, a color, and the data type's range for each channel. */
    private Omero omero(List<Axis> axisList, Input in) {
        int c = -1;
        for (int d = 0; d < axisList.size(); d++) {
            if (axisList.get(d).isChannel()) {
                c = d;
            }
        }
        long count = c < 0 ? 1 : in.pixels().shape()[c];
        String[] labels = channelNames == null ? new String[0] : channelNames.split(",", -1);
        String[] colors = channelColors == null ? new String[0] : channelColors.split(",", -1);
        if (labels.length > count || colors.length > count) {
            throw new UsageException("more channel names or colors than the " + count + " channels");
        }
        double[] range = range(in.pixels().dataType());
        String[] defaults = {"FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "FFFFFF"};
        List<Omero.Channel> channels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String color = i < colors.length ? colors[i].strip() : count == 1 ? "FFFFFF" : defaults[i % defaults.length];
            if (!color.matches("[0-9A-Fa-f]{6}")) {
                throw new UsageException("a channel color is six hexadecimal digits, not '" + color + "'");
            }
            channels.add(Omero.Channel.of(i < labels.length ? labels[i].strip() : "channel " + i,
                    color.toUpperCase(Locale.ROOT), new Omero.Window(range[0], range[1], range[0], range[1])));
        }
        return Omero.of(channels);
    }

    private static double[] range(DataType type) {
        int bits = 8 * type.byteCount();
        return switch (type.kind()) {
            case BOOL -> new double[] {0, 1};
            case UINT -> new double[] {0, bits == 64 ? new BigInteger("18446744073709551615").doubleValue()
                    : Math.pow(2, bits) - 1};
            case INT -> new double[] {-Math.pow(2, bits - 1), Math.pow(2, bits - 1) - 1};
            default -> new double[] {0, 1};
        };
    }

    /** The axes: given, or the Zarr array's dimension names, or the usual ones for the rank. */
    private List<Axis> axes(Input in) {
        int rank = in.pixels().shape().length;
        List<String> names;
        if (axes != null) {
            names = List.of(names(axes));
        } else if (in.dimensionNames().isPresent()) {
            names = in.dimensionNames().get();
        } else {
            names = switch (rank) {
                case 2 -> List.of("y", "x");
                case 3 -> List.of("c", "y", "x");
                case 4 -> List.of("c", "z", "y", "x");
                case 5 -> List.of("t", "c", "z", "y", "x");
                default -> throw new UsageException("an OME-Zarr image has 2 to 5 dimensions; the input has " + rank);
            };
        }
        if (names.size() != rank) {
            throw new UsageException(names.size() + " axes for an input of " + rank + " dimensions");
        }
        String space = unit != null ? unit : pixelSize != null ? "micrometer" : null;
        List<Axis> list = new ArrayList<>();
        for (String n : names) {
            list.add(switch (n.toLowerCase(Locale.ROOT)) {
                case "x", "y", "z" -> Axis.space(n, space);
                case "c" -> Axis.channel(n);
                case "t" -> Axis.time(n, timeUnit);
                default -> Axis.of(n, null, null);
            });
        }
        return list;
    }

    /** Names joined by commas, or a word of single letters. */
    private static String[] names(String spec) {
        String s = spec.strip();
        return s.contains(",") ? Arrays.stream(s.split(",")).map(String::strip).toArray(String[]::new)
                : s.chars().mapToObj(ch -> String.valueOf((char) ch)).toArray(String[]::new);
    }

    private static double[] numbers(String spec, String option) {
        try {
            return Arrays.stream(spec.split(",")).map(String::strip).mapToDouble(Double::parseDouble).toArray();
        } catch (NumberFormatException e) {
            throw new UsageException(option + " is numbers joined by commas, not '" + spec + "'");
        }
    }

    private static long[] longs(String spec, String option) {
        try {
            return Arrays.stream(spec.split(",")).map(String::strip).mapToLong(Long::parseLong).toArray();
        } catch (NumberFormatException e) {
            throw new UsageException(option + " is whole numbers joined by commas, not '" + spec + "'");
        }
    }

    /**
     * The input array.
     *
     * @param pixels         its pixels
     * @param name           its name
     * @param dimensionNames its dimension names, if it has them
     */
    private record Input(PixelSource pixels, String name, Optional<List<String>> dimensionNames) {
    }

    private static Input input(Sources.Source source, String path) throws Exception {
        return switch (source) {
            case Sources.ZarrSource z -> {
                ZarrNode node = ZarrInspect.resolve(z.store(), path);
                if (!(node instanceof ZarrArray array)) {
                    throw new UsageException(ZarrInspect.display(node) + " is a group: give the path of an array");
                }
                if (!PixelSource.isNumeric(array.dataType())) {
                    throw new UsageException("an image's pixels are numbers; " + ZarrInspect.display(node) + " is "
                            + array.dataType());
                }
                Optional<List<String>> names = array.dimensionNames()
                        .filter(l -> l.stream().allMatch(s -> s != null && !s.isEmpty()));
                yield new Input(PixelSource.of(array), ConvertCommand.rootName(source.location(), path), names);
            }
            case Sources.Hdf5Source h -> {
                Hdf5Object object = Hdf5Inspect.resolve(h.file(), path);
                if (!(object instanceof Dataset dataset)) {
                    throw new UsageException(object.path() + " is a group: give the path of a dataset");
                }
                yield new Input(hdf5(dataset), ConvertCommand.rootName(source.location(), path), Optional.empty());
            }
        };
    }

    /** An HDF5 dataset's pixels, as little-endian elements of the Zarr type falcon convert gives them. */
    private static PixelSource hdf5(Dataset dataset) throws Exception {
        Dataspace space = dataset.dataspace();
        if (space.kind() == Dataspace.Kind.NULL) {
            throw new UsageException(dataset.path() + " has no elements");
        }
        Hdf5ToZarr.Mapping mapping;
        try {
            mapping = Hdf5ToZarr.map(dataset.datatype());
        } catch (Hdf5ToZarr.Unsupported e) {
            throw new UsageException("an image's pixels are numbers; " + dataset.path() + " is " + e.getMessage());
        }
        DataType type = mapping.type();
        if (!PixelSource.isNumeric(type) || mapping.transfer() == Hdf5ToZarr.Transfer.STRUCT) {
            throw new UsageException("an image's pixels are numbers; " + dataset.path() + " is "
                    + Describe.type(dataset.datatype()));
        }
        long[] shape = space.dimensions();
        return new PixelSource() {
            @Override
            public long[] shape() {
                return shape.clone();
            }

            @Override
            public DataType dataType() {
                return type;
            }

            @Override
            public byte[] read(long[] offset, long[] count) {
                var selection = dataset.select(offset, count);
                int size = type.byteCount();
                return switch (mapping.transfer()) {
                    case RAW -> {
                        byte[] raw = selection.readRawBytes();
                        if (mapping.order() == ByteOrder.BIG_ENDIAN && size > 1) {
                            for (int e = 0; e < raw.length; e += size) {
                                for (int i = 0, j = size - 1; i < j; i++, j--) {
                                    byte t = raw[e + i];
                                    raw[e + i] = raw[e + j];
                                    raw[e + j] = t;
                                }
                            }
                        }
                        yield raw;
                    }
                    case LONGS -> {
                        long[] values = selection.readLongs();
                        ByteBuffer b = ByteBuffer.allocate(values.length * size).order(ByteOrder.LITTLE_ENDIAN);
                        for (long v : values) {
                            for (int i = 0; i < size; i++) {
                                b.put((byte) (v >>> (8 * i)));
                            }
                        }
                        yield b.array();
                    }
                    case FLOATS -> {
                        float[] values = selection.readFloats();
                        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                        for (float v : values) {
                            b.putFloat(v);
                        }
                        yield b.array();
                    }
                    case DOUBLES -> {
                        double[] values = selection.readDoubles();
                        ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
                        for (double v : values) {
                            b.putDouble(v);
                        }
                        yield b.array();
                    }
                    default -> throw new IllegalStateException("not pixels: " + mapping.transfer());
                };
            }
        };
    }
}
