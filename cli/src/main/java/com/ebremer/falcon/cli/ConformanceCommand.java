package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.ebremer.falcon.hdf5.Hdf5File;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code falcon conformance}: the command lines conformance tests call.
 * <ul>
 *   <li>{@code --array_path=<array>}: the Zarr community's conformance tests
 *       (<a href="https://github.com/Bisaloo/zarr-conformance-tests">zarr-conformance-tests</a>, which zarr-java
 *       runs too) read every value of the array there and print them, as {@code dump} does, and the status
 *       says whether it could. The tests run it as {@code java -jar falcon.jar conformance}.</li>
 *   <li>{@code --hdf5=<file>}: Falcon's HDF5 conformance harness
 *       ({@code tools/conformance/run_hdf5_conformance.py}) prints a JSON manifest of everything in a local HDF5
 *       file (see {@link Hdf5Manifest}) and compares it with what h5py reads. The status is 0 if the file
 *       opened, whatever in it could not be read, and 1 if it did not; the line is printed either way.</li>
 * </ul>
 */
@Parameters(commandDescription = "Read every value of a Zarr array and print them: the command line the Zarr "
        + "conformance tests call; or print a JSON manifest of an HDF5 file", separators = "=")
final class ConformanceCommand implements Command {

    @Parameter(names = "--array_path", order = 0, description = "The Zarr array to read: a directory, a .zip, "
            + "or an http(s):// or s3:// URL")
    String arrayPath;

    @Parameter(names = "--hdf5", order = 1, description = "A local HDF5 file: print a JSON manifest of every "
            + "link, object, attribute, and value in it, for Falcon's HDF5 conformance harness")
    String hdf5;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        if ((arrayPath == null) == (hdf5 == null)) {
            throw new UsageException("give one of --array_path and --hdf5");
        }
        if (hdf5 != null) {
            String manifest;
            int status = 0;
            try (Hdf5File file = Hdf5File.open(Path.of(hdf5))) {
                manifest = Hdf5Manifest.of(file);
            } catch (Exception | StackOverflowError | OutOfMemoryError e) {
                manifest = Hdf5Manifest.failure(e);
                status = 1;
            }
            context.out.println(manifest);
            return status;
        }
        DumpCommand dump = new DumpCommand();
        dump.arguments = List.of(arrayPath, "/");
        return dump.run(context);
    }
}
