package com.ebremer.falcon.cli;

import java.util.List;

/** One of the {@code falcon} command's commands: its options, which JCommander fills in, and what it does. */
interface Command {

    /** {@return the options every command takes} */
    CommonOptions options();

    /**
     * Runs the command.
     *
     * @param context where the command writes, and the clients it shares
     * @return the exit status
     * @throws Exception if the command fails
     */
    int run(Context context) throws Exception;

    /**
     * Checks the number of a command's arguments.
     *
     * @param arguments the arguments given
     * @param min       the fewest the command takes
     * @param max       the most it takes
     * @param synopsis  how the command's usage names them, such as {@code <source> [<path>]}
     * @throws UsageException if there are too few or too many
     */
    static void requireArguments(List<String> arguments, int min, int max, String synopsis) {
        if (arguments.size() < min || arguments.size() > max) {
            throw new UsageException((arguments.size() < min ? "too few" : "too many") + " arguments: expected "
                    + synopsis);
        }
    }
}
