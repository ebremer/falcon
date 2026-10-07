package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.ebremer.falcon.ome.OmeValidator;
import com.ebremer.falcon.ome.ValidationReport;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code falcon ome validate}: checks an OME-Zarr group, and the hierarchy below it, against the specification
 * of its version. With {@code --json} it prints the {@code {"valid": ..., "message": ...}} object the
 * specification's conformance tool reads, so it can be that tool's command ("dingus").
 */
@Parameters(commandDescription = "Check an OME-Zarr group (0.4, 0.5, or 0.6), and the hierarchy below it, against "
        + "the specification", separators = "=")
final class OmeValidateCommand implements Command {

    @Parameter(description = "<source> [<path>]")
    List<String> arguments = new ArrayList<>();

    @Parameter(names = "--strict", order = 0, description = "Also require the recommended fields the specification's "
            + "strict schemas require (an image's name, type, and metadata; a label image's colors; ...)")
    boolean strict;

    @Parameter(names = "--metadata-only", order = 1, description = "Check the group's metadata alone, not the arrays "
            + "and groups it refers to")
    boolean metadataOnly;

    @Parameter(names = "--attributes", order = 2, description = "Check a JSON file of one group's attributes "
            + "instead of a store (its metadata alone)")
    String attributes;

    @Parameter(names = "--errors-only", order = 3, description = "Do not list the warnings")
    boolean errorsOnly;

    @Parameter(names = "--json", order = 4, description = "Print {\"valid\": true|false, \"message\": ...}, and exit "
            + "with 0 whether valid or not (the conformance tests' interface)")
    boolean json;

    @ParametersDelegate
    CommonOptions options = new CommonOptions();

    @Override
    public CommonOptions options() {
        return options;
    }

    @Override
    public int run(Context context) throws Exception {
        OmeValidator validator = new OmeValidator().strict(strict).metadataOnly(metadataOnly);
        ValidationReport report;
        String what;
        if (attributes != null) {
            Command.requireArguments(arguments, 0, 0, "no <source> with --attributes");
            JsonValue value = Json.parse(Files.readAllBytes(Path.of(attributes)));
            if (!(value instanceof JsonObject o)) {
                throw new IllegalArgumentException(attributes + " holds a JSON " + value.typeName() + ", not an object");
            }
            report = validator.validate(o);
            what = attributes;
        } else {
            Command.requireArguments(arguments, 1, 2, "<source> [<path>]");
            String path = arguments.size() > 1 ? arguments.get(1) : "/";
            try (Sources.ZarrSource source = Sources.openZarr(context, arguments.get(0))) {
                ZarrNode node = ZarrInspect.resolve(source.store(), path);
                if (!(node instanceof ZarrGroup group)) {
                    throw new UsageException(ZarrInspect.display(node) + " is an array: OME-Zarr metadata is in groups");
                }
                report = validator.validate(group);
            }
            what = arguments.get(0) + (arguments.size() > 1 ? " " + path : "");
        }
        if (json) {
            Map<String, JsonValue> o = new LinkedHashMap<>();
            o.put("valid", JsonBool.of(report.isValid()));
            List<ValidationReport.Issue> shown = report.isValid() ? report.warnings() : report.errors();
            if (!shown.isEmpty()) {
                o.put("message", new JsonString(shown.stream().map(ValidationReport.Issue::toString)
                        .collect(Collectors.joining("; "))));
            }
            context.out.println(Json.write(new JsonObject(o)));
            return 0;
        }
        for (ValidationReport.Issue issue : report.issues()) {
            if (!errorsOnly || issue.severity() == ValidationReport.Severity.ERROR) {
                context.out.println(issue);
            }
        }
        int errors = report.errors().size();
        int warnings = report.warnings().size();
        context.out.println(what + ": " + (errors == 0 ? "valid" : "invalid, " + errors + (errors == 1 ? " error" : " errors"))
                + (warnings == 0 ? "" : (errors == 0 ? ", " : " and ") + warnings
                + (warnings == 1 ? " warning" : " warnings")));
        return errors == 0 ? 0 : Falcon.FAILED;
    }
}
