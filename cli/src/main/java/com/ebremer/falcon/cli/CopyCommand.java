package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.List;

/** {@code falcon copy}: a Zarr store copied as it is, or re-encoded. */
@Parameters(commandDescription = "Copy a Zarr store as it is (every key, byte for byte), or re-encoded in another "
        + "Zarr format, compression, or chunking: between directories, .zip archives, HTTP (to read), and S3")
final class CopyCommand implements Command {

    @Parameter(description = "<source> <target>")
    List<String> arguments = new ArrayList<>();

    @Parameter(names = "--path", order = 0, description = "Copy only this group or array of the source")
    String path = "/";

    @Parameter(names = "--zarr-format", order = 1, description = "Re-encode in this Zarr format, 2 or 3")
    int zarrFormat;

    @Parameter(names = {"-c", "--compression"}, order = 2, description = "Re-encode with this compression: keep, "
            + "none, gzip[:level], zstd[:level], blosc[:cname[:clevel[:shuffle]]], bz2[:level], or lz4")
    String compression;

    @Parameter(names = "--chunks", order = 3, description = "Re-encode with these chunks: keep or auto (chosen as "
            + "zarr-python chooses them)")
    String chunks;

    @Parameter(names = "--consolidate", order = 4, description = "Write the copy's consolidated metadata")
    boolean consolidate;

    @Parameter(names = "--overwrite", order = 5, description = "Replace what the target holds")
    boolean overwrite;

    @Parameter(names = {"-j", "--threads"}, order = 6, description = "How many keys or blocks to copy at once")
    int threads = ConvertCommand.defaultThreads();

    @Parameter(names = {"-q", "--quiet"}, order = 7, description = "Do not list each array written")
    boolean quiet;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        Command.requireArguments(arguments, 2, 2, "<source> <target>");
        if (zarrFormat != 0 && zarrFormat != 2 && zarrFormat != 3) {
            throw new UsageException("--zarr-format is 2 or 3, not " + zarrFormat);
        }
        if (threads < 1) {
            throw new UsageException("--threads must be at least 1");
        }
        boolean reencode = zarrFormat != 0 || compression != null || chunks != null;
        Compression.Policy policy = compression == null ? new Compression.Keep() : Compression.Policy.parse(compression);
        if (policy instanceof Compression.Fixed f && f.compression().kind() == Compression.Kind.LZF) {
            throw new UsageException("Zarr has no LZF compression: choose another --compression");
        }
        boolean autoChunks = chunks != null && ConvertCommand.chunks(chunks);
        ConvertSettings settings = new ConvertSettings(zarrFormat, policy, autoChunks, threads, quiet);
        String output = arguments.get(1);
        String node = path.replaceAll("^/+|/+$", "");
        try (Sources.ZarrSource source = Sources.openZarr(context, arguments.get(0));
             Sources.ZarrTarget target = Sources.zarrTarget(context, output, overwrite)) {
            ZarrCopy copy = new ZarrCopy(context, settings);
            Store store = target.store();
            if (reencode) {
                try {
                    copy.reencode(source.store(), node, store, overwrite);
                } catch (IllegalArgumentException e) {
                    throw overwrite ? e : new IllegalArgumentException(e.getMessage()
                            + " (pass --overwrite to replace what is there)", e);
                }
                ConvertCommand.summary(context, copy.arrays, copy.groups, output);
            } else {
                if (holdsNode(store)) {
                    if (!overwrite) {
                        throw new IllegalArgumentException(output + " already holds a Zarr store (pass --overwrite to "
                                + "replace it)");
                    }
                    for (String key : store.list()) {
                        store.delete(key);
                    }
                }
                copy.copyKeys(source.store(), node, store);
                context.out.println("Copied " + copy.keys.get() + (copy.keys.get() == 1 ? " key, " : " keys, ")
                        + Describe.bytes(copy.bytes.get()) + ", to " + output);
            }
            if (consolidate) {
                Zarr.openGroup(store, false).consolidate();
            }
        }
        return 0;
    }

    private static boolean holdsNode(Store store) {
        return store.exists("zarr.json") || store.exists(".zgroup") || store.exists(".zarray");
    }
}
