package com.ebremer.falcon.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The {@code falcon} command: {@code ls}, {@code info}, and {@code dump} read HDF5 files and Zarr stores;
 * {@code convert} turns one format into the other; {@code copy} and {@code consolidate} work on Zarr;
 * {@code ome validate} checks OME-Zarr and {@code ome pyramid} writes it.
 *
 * <pre>{@code
 * java -jar falcon.jar ls -r scan.h5
 * java -jar falcon.jar dump scan.h5 /images/frame --slice 0,0:4,0:4
 * java -jar falcon.jar convert scan.h5 scan.zarr
 * java -jar falcon.jar copy s3://bucket/image.zarr image.zarr.zip
 * }</pre>
 *
 * <p>A source is a local HDF5 file, a Zarr store in a directory or a ZIP archive, an {@code http(s)://} URL
 * of either, or an {@code s3://bucket/key} URL of either. {@code falcon --help} lists the commands, and
 * {@code falcon <command> --help} a command's options.
 */
public final class Falcon {

    /** The exit status of a command that failed. */
    static final int FAILED = 1;
    /** The exit status of a command line that could not be parsed. */
    static final int USAGE = 2;

    private Falcon() {
    }

    /**
     * Runs the command its arguments name, and exits with its status: 0 if it succeeded, 1 if it failed, and
     * 2 if the command line was wrong.
     *
     * @param args the command line, such as {@code ls -r scan.h5}
     */
    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        System.out.flush();
        System.exit(status);
    }

    /**
     * Runs the command its arguments name, writing its output to {@code out} and its messages and errors to
     * {@code err}.
     *
     * @param args the command line, such as {@code ls -r scan.h5}
     * @param out  where the command's output goes
     * @param err  where warnings, errors, and usage go
     * @return the exit status: 0 if the command succeeded, 1 if it failed, and 2 if the command line was
     *         wrong
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Main main = new Main();
        Map<String, Command> commands = new LinkedHashMap<>();
        commands.put("ls", new LsCommand());
        commands.put("info", new InfoCommand());
        commands.put("dump", new DumpCommand());
        commands.put("convert", new ConvertCommand());
        commands.put("copy", new CopyCommand());
        commands.put("consolidate", new ConsolidateCommand());
        commands.put("conformance", new ConformanceCommand());
        // commands with commands of their own: falcon ome validate, falcon ome pyramid
        Map<String, Group> groups = new LinkedHashMap<>();
        groups.put("ome", new Group(new OmeGroup(), Map.of("validate", new OmeValidateCommand(),
                "pyramid", new OmePyramidCommand()), OmeGroup.OVERVIEW));
        JCommander.Builder builder = JCommander.newBuilder().programName("falcon").addObject(main)
                .expandAtSign(false).columnSize(100);
        commands.forEach(builder::addCommand);
        groups.forEach((name, group) -> builder.addCommand(name, group.options()));
        JCommander parser = builder.build();
        groups.forEach((name, group) -> {
            JCommander groupParser = parser.getCommands().get(name);
            new java.util.TreeMap<>(group.commands()).forEach(groupParser::addCommand);
        });
        try {
            parser.parse(args);
        } catch (ParameterException e) {
            err.println("falcon: " + e.getMessage());
            String command = parser.getParsedCommand();
            if (command != null && groups.containsKey(command)) {
                String sub = parser.getCommands().get(command).getParsedCommand();
                command = sub == null ? command : command + " " + sub;
            }
            err.println(command == null ? "Run 'falcon --help' for the commands."
                    : "Run 'falcon " + command + " --help' for its options.");
            return USAGE;
        }
        if (main.version) {
            out.println("falcon " + version());
            return 0;
        }
        String name = parser.getParsedCommand();
        if (name == null) {
            PrintStream to = main.help ? out : err;
            to.print(overview());
            return main.help ? 0 : USAGE;
        }
        Command command;
        JCommander commandParser;
        if (groups.containsKey(name)) {
            Group group = groups.get(name);
            JCommander groupParser = parser.getCommands().get(name);
            String sub = groupParser.getParsedCommand();
            if (sub == null) {
                boolean help = main.help || group.options().help;
                (help ? out : err).print(group.overview());
                return help ? 0 : USAGE;
            }
            command = group.commands().get(sub);
            commandParser = groupParser.getCommands().get(sub);
            name = name + " " + sub;
        } else {
            command = commands.get(name);
            commandParser = parser.getCommands().get(name);
        }
        if (main.help || command.options().help) {
            StringBuilder usage = new StringBuilder();
            commandParser.setProgramName("falcon " + name);
            commandParser.getUsageFormatter().usage(usage);
            out.print(usage);
            return 0;
        }
        try (Context context = new Context(out, err, command.options())) {
            return command.run(context);
        } catch (UsageException e) {
            err.println("falcon " + name + ": " + e.getMessage());
            err.println("Run 'falcon " + name + " --help' for its options.");
            return USAGE;
        } catch (Exception | StackOverflowError e) {
            err.println("falcon " + name + ": " + Errors.describe(e));
            if (command.options().verbose) {
                e.printStackTrace(err);
            }
            return FAILED;
        }
    }

    /**
     * A command with commands of its own.
     *
     * @param options  its own options (just {@code --help})
     * @param commands its commands, by name
     * @param overview what {@code falcon <group> --help} prints
     */
    private record Group(OmeGroup options, Map<String, Command> commands, String overview) {
    }

    /** {@code falcon ome}: the OME-Zarr commands. */
    @com.beust.jcommander.Parameters(commandDescription = "OME-Zarr: validate an image, plate, or scene; write an "
            + "image pyramid")
    static final class OmeGroup {
        static final String OVERVIEW = """
                Usage: falcon ome <command> [options] <arguments>

                Commands:
                  validate  Check an OME-Zarr group (0.4, 0.5, or 0.6), and the hierarchy below it, against the
                            specification
                  pyramid   Write an OME-Zarr image from an array, building its smaller levels; or a label image

                Run 'falcon ome <command> --help' for a command's options.
                """;

        @Parameter(names = {"-h", "--help"}, help = true, description = "Show the OME-Zarr commands")
        boolean help;
    }

    private static String overview() {
        return """
                Usage: falcon <command> [options] <arguments>

                Commands:
                  ls           List the groups and arrays of an HDF5 file or a Zarr store
                  info         Describe a group or an array: its type, shape, chunks, compression, attributes
                  dump         Print an array's values, or an attribute's
                  convert      Convert an HDF5 file to Zarr, or a Zarr store to HDF5
                  copy         Copy a Zarr store, or re-encode it (Zarr v2 or v3, compression, chunks)
                  consolidate  Write a Zarr group's consolidated metadata
                  conformance  Read a Zarr array's values: the command the Zarr conformance tests call
                  ome          OME-Zarr: 'ome validate' an image, plate, or scene; 'ome pyramid' write an image

                A source is a local HDF5 file, a Zarr store (a directory, or a .zip of one), or an http(s):// or
                s3://bucket/key URL of either.

                Options:
                  -h, --help   Show this overview, or with a command, the command's options
                  --version    Show Falcon's version

                Run 'falcon <command> --help' for a command's options.
                """;
    }

    /** {@return this build's version, as the build recorded it} */
    static String version() {
        Properties properties = new Properties();
        try (InputStream in = Falcon.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version", "unknown");
    }

    /** The options before the command. */
    static final class Main {
        @Parameter(names = {"-h", "--help"}, help = true, description = "Show the commands, or a command's options")
        boolean help;

        @Parameter(names = "--version", description = "Show Falcon's version")
        boolean version;
    }
}
