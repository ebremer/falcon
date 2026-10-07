package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import java.util.List;

/**
 * {@code falcon conformance}: the command line the Zarr community's conformance tests
 * (<a href="https://github.com/Bisaloo/zarr-conformance-tests">zarr-conformance-tests</a>, which zarr-java
 * runs too) call: {@code --array_path=<array>} reads every value of the array there and prints them, as
 * {@code dump} does, and the status says whether it could. The tests run it as
 * {@code java -jar falcon.jar conformance}.
 */
@Parameters(commandDescription = "Read every value of a Zarr array and print them: the command line the Zarr "
        + "conformance tests call", separators = "=")
final class ConformanceCommand implements Command {

    @Parameter(names = "--array_path", required = true, order = 0, description = "The Zarr array to read: a "
            + "directory, a .zip, or an http(s):// or s3:// URL")
    String arrayPath;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        DumpCommand dump = new DumpCommand();
        dump.arguments = List.of(arrayPath, "/");
        return dump.run(context);
    }
}
