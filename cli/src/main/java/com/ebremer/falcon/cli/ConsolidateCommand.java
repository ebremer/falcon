package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrGroup;
import java.util.ArrayList;
import java.util.List;

/** {@code falcon consolidate}: a Zarr group's consolidated metadata, written anew. */
@Parameters(commandDescription = "Write a Zarr group's consolidated metadata: one document describing every node "
        + "below it, so that readers learn the hierarchy in one request")
final class ConsolidateCommand implements Command {

    @Parameter(description = "<store> [<path>]")
    List<String> arguments = new ArrayList<>();

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        Command.requireArguments(arguments, 1, 2, "<store> [<path>]");
        String location = arguments.get(0);
        String path = arguments.size() > 1 ? arguments.get(1) : "";
        if (Sources.isHttp(location)) {
            throw new UsageException("an http(s):// store cannot be written");
        }
        try (Sources.Source source = Sources.open(context, location)) {
            if (!(source instanceof Sources.ZarrSource)) {
                throw new UsageException(location + " is an HDF5 file, not a Zarr store");
            }
        }
        try (Sources.ZarrTarget target = Sources.zarrTarget(context, location, false)) {
            String p = path.replaceAll("^/+|/+$", "");
            ZarrGroup group = Zarr.open(target.store(), p, false).asGroup();
            ZarrGroup consolidated = group.consolidate();
            context.out.println("Consolidated " + consolidated.childNames().size() + " members of /" + p + " in "
                    + location);
        }
        return 0;
    }
}
