package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.ebremer.falcon.zarr.Zarr;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code falcon convert}: an HDF5 file to Zarr, or a Zarr store to HDF5. */
@Parameters(commandDescription = "Convert an HDF5 file to a Zarr store (a directory, a .zip, or an s3:// prefix), "
        + "or a Zarr store to an HDF5 file")
final class ConvertCommand implements Command {

    @Parameter(description = "<input> <output>")
    List<String> arguments = new ArrayList<>();

    @Parameter(names = "--path", order = 0, description = "Convert only this group or array of the input")
    String path = "/";

    @Parameter(names = "--zarr-format", order = 1, description = "The Zarr format to write, 2 or 3")
    int zarrFormat = 3;

    @Parameter(names = {"-c", "--compression"}, order = 2, description = "auto (the input's compressor where the "
            + "output has it, else zstd for Zarr; deflate for HDF5), keep (also HDF5's plugin filters), none, "
            + "gzip[:level], zstd[:level], blosc[:cname[:clevel[:shuffle]]], bz2[:level], lz4, or lzf (HDF5 only)")
    String compression = "auto";

    @Parameter(names = "--chunks", order = 3, description = "keep (the input's chunks, where it has them) or auto "
            + "(chosen as h5py and zarr-python choose them)")
    String chunks = "keep";

    @Parameter(names = "--consolidate", order = 4, description = "Write the Zarr store's consolidated metadata too")
    boolean consolidate;

    @Parameter(names = "--overwrite", order = 5, description = "Replace the output if it exists")
    boolean overwrite;

    @Parameter(names = {"-j", "--threads"}, order = 6, description = "How many blocks to convert at once")
    int threads = defaultThreads();

    @Parameter(names = {"-q", "--quiet"}, order = 7, description = "Do not list each array written")
    boolean quiet;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    static int defaultThreads() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    @Override
    public int run(Context context) throws Exception {
        Command.requireArguments(arguments, 2, 2, "<input> <output>");
        if (zarrFormat != 2 && zarrFormat != 3) {
            throw new UsageException("--zarr-format is 2 or 3, not " + zarrFormat);
        }
        boolean autoChunks = chunks(chunks);
        if (threads < 1) {
            throw new UsageException("--threads must be at least 1");
        }
        Compression.Policy policy = Compression.Policy.parse(compression);
        ConvertSettings settings = new ConvertSettings(zarrFormat, policy, autoChunks, threads, quiet);
        String input = arguments.get(0);
        String output = arguments.get(1);
        try (Sources.Source source = Sources.open(context, input)) {
            switch (source) {
                case Sources.Hdf5Source h -> {
                    if (policy instanceof Compression.Fixed f && f.compression().kind() == Compression.Kind.LZF) {
                        throw new UsageException("Zarr has no LZF compression: choose another --compression");
                    }
                    try (Sources.ZarrTarget target = Sources.zarrTarget(context, output, overwrite)) {
                        Hdf5ToZarr converter = new Hdf5ToZarr(context, settings);
                        try {
                            converter.convert(h.file(), path, target.store(), overwrite);
                        } catch (IllegalArgumentException e) {
                            throw overwrite ? e : new IllegalArgumentException(e.getMessage()
                                    + " (pass --overwrite to replace what is there)", e);
                        }
                        if (consolidate && converter.groups > 0) {
                            Zarr.openGroup(target.store(), false).consolidate();
                        }
                        summary(context, converter.arrays, converter.groups, output);
                    }
                }
                case Sources.ZarrSource z -> {
                    if (consolidate) {
                        throw new UsageException("--consolidate applies to a Zarr output, not an HDF5 file");
                    }
                    Path target = Sources.hdf5Target(output, overwrite);
                    ZarrToHdf5 converter = new ZarrToHdf5(context, settings);
                    converter.convert(z.store(), path, target, rootName(input, path));
                    summary(context, converter.arrays, converter.groups, output);
                }
            }
        }
        return 0;
    }

    /**
     * {@return whether {@code --chunks} asks for chunks chosen anew}
     *
     * @throws UsageException if it is neither {@code keep} nor {@code auto}
     */
    static boolean chunks(String chunks) {
        return switch (chunks.toLowerCase(Locale.ROOT)) {
            case "keep" -> false;
            case "auto" -> true;
            default -> throw new UsageException("--chunks is keep or auto, not '" + chunks + "'");
        };
    }

    static void summary(Context context, int arrays, int groups, String output) {
        String what = arrays + (arrays == 1 ? " array" : " arrays") + (groups == 0 ? ""
                : " and " + groups + (groups == 1 ? " group" : " groups"));
        int warnings = context.warnings();
        context.out.println("Wrote " + what + " to " + output
                + (warnings == 0 ? "" : " (" + warnings + (warnings == 1 ? " warning" : " warnings") + " above)"));
    }

    /** The name of the one dataset a Zarr array becomes: its own, or its store's, less any extension. */
    static String rootName(String location, String path) {
        String p = path.replaceAll("/+$", "");
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (name.isEmpty()) {
            String l = location.replaceAll("[/\\\\]+$", "");
            name = l.substring(Math.max(l.lastIndexOf('/'), l.lastIndexOf('\\')) + 1);
            name = name.replaceAll("(?i)(\\.zarr)?(\\.zip)?$", "");
        }
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "data" : name;
    }
}
