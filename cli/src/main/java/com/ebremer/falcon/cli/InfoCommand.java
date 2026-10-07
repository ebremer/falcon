package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import java.util.ArrayList;
import java.util.List;

/** {@code falcon info}: everything about one group or array. */
@Parameters(commandDescription = "Describe a group or an array: its type, shape, chunks, compression, fill value, "
        + "storage, and attributes")
final class InfoCommand implements Command {

    @Parameter(description = "<source> [<path>]")
    List<String> arguments = new ArrayList<>();

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
                case Sources.Hdf5Source h -> new Hdf5Inspect(context).info(h.file(), path);
                case Sources.ZarrSource z -> new ZarrInspect(context).info(z.store(), path);
            }
        }
        return 0;
    }
}
