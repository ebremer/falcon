package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import java.util.ArrayList;
import java.util.List;

/** {@code falcon ls}: the members of a group, or every node below it. */
@Parameters(commandDescription = "List the groups and arrays of an HDF5 file or a Zarr store: each one's shape, "
        + "type, chunks, and compression")
final class LsCommand implements Command {

    @Parameter(description = "<source> [<path>]")
    List<String> arguments = new ArrayList<>();

    @Parameter(names = {"-r", "--recursive"}, order = 0, description = "List everything below the group, with full paths")
    boolean recursive;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        Command.requireArguments(arguments, 1, 2, "<source> [<path>]");
        String path = arguments.size() > 1 ? arguments.get(1) : "/";
        try (Sources.Source source = Sources.open(context, arguments.get(0))) {
            switch (source) {
                case Sources.Hdf5Source h -> new Hdf5Inspect(context).ls(h.file(), path, recursive);
                case Sources.ZarrSource z -> new ZarrInspect(context).ls(z.store(), path, recursive);
            }
        }
        return 0;
    }
}
