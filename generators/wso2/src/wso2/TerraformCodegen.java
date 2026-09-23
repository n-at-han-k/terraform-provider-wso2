package wso2;

import io.swagger.v3.oas.models.Operation;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.TerraformProviderCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.OperationsMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.openapitools.codegen.utils.StringUtils.camelize;
import static org.openapitools.codegen.utils.StringUtils.underscore;

/**
 * The terraform-provider generator, grouped by RESOURCE instead of by tag.
 *
 * Upstream keys the operation map on the tag and then asks
 * {@code CodegenOperation.isRestfulCreate()} and friends which of them is the
 * create, the read, the update. Those helpers strip {@code "/" + baseName}
 * from the path -- and WSO2 tags {@code /organizations} with the singular
 * {@code Organization}, so the strip leaves {@code "s"}, nothing looks
 * RESTful, and every resource comes out with an empty schema and a Create
 * that refuses.
 *
 * A Terraform resource is a collection path plus its member path, which is
 * what this class keys on: {@code /organizations} and
 * {@code /organizations/{organization-id}} are one resource, and baseName is
 * the collection segment the paths actually spell. Upstream's detection then
 * works as written.
 *
 * Everything else -- the schema, the CRUD bodies, the client -- is upstream's.
 */
public class TerraformCodegen extends TerraformProviderCodegen {

    public static final String RESOURCE_PATHS = "resourcePaths";

    /**
     * Not a comma: --additional-properties is itself comma-separated, so a
     * comma here ends the property rather than separating two paths.
     */
    private static final String SEPARATOR = "[;|\\s]+";

    /** Collection paths to generate; empty means every path in the document. */
    private final Set<String> wanted = new LinkedHashSet<>();

    public TerraformCodegen() {
        super();
        cliOptions.add(new CliOption(RESOURCE_PATHS,
                "Collection paths to generate as resources, separated by ';' (default: all)"));
    }

    @Override
    public String getName() {
        return "wso2-terraform";
    }

    @Override
    public String getHelp() {
        return "Generates a Terraform provider, one resource per collection path rather than per tag.";
    }

    @Override
    public void processOpts() {
        super.processOpts();

        // What makes the output DEPLOYABLE rather than merely compilable: the
        // image that carries the binary and the workflow that publishes it.
        // Upstream emits the Go, a go.mod and a GNUmakefile and stops there,
        // because it has no opinion about where a provider with no registry
        // ends up. We do: an initContainer copies the binary out of this image
        // into provider-opentofu's filesystem mirror.
        //
        // These live under the same resource directory name as upstream's
        // templates and resolve off the classpath, our jar first -- so the
        // stock templates still come from the CLI's jar and no -t is needed.
        supportingFiles.add(new SupportingFile("Dockerfile.mustache", "", "Dockerfile"));
        supportingFiles.add(new SupportingFile("dockerignore.mustache", "", ".dockerignore"));
        supportingFiles.add(new SupportingFile("build_image_workflow.mustache",
                ".github" + File.separator + "workflows", "build-image.yml"));

        Object paths = additionalProperties.get(RESOURCE_PATHS);
        if (paths != null && !paths.toString().isEmpty()) {
            Arrays.stream(paths.toString().split(SEPARATOR))
                    .map(String::trim)
                    .filter(path -> !path.isEmpty())
                    .forEach(wanted::add);
        }
    }

    /**
     * The group key is the collection path, so the member operations land with
     * the collection's own. {@code co.baseName} is the collection segment,
     * because that is what {@code pathWithoutBaseName()} strips.
     */
    @Override
    public void addOperationToGroup(String tag, String resourcePath, Operation operation,
                                    CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        String collection = collectionOf(resourcePath);

        if (!wanted.isEmpty() && !wanted.contains(collection)) {
            return;
        }

        List<CodegenOperation> group = operations.computeIfAbsent(collection, key -> new ArrayList<>());

        // An operation carrying two tags is offered once per tag; here both
        // offers name the same group, so the second one is a duplicate.
        if (group.stream().anyMatch(existing -> existing.operationId.equals(co.operationId))) {
            return;
        }

        group.add(co);
        co.baseName = lastSegment(collection);
    }

    /**
     * Upstream takes whichever of PUT and PATCH the document lists first as
     * the update. A PATCH body here is a list of patch operations, and what
     * the resource sends is the whole model -- so where a document offers
     * both, the update is the PUT.
     */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        for (CodegenOperation op : objs.getOperations().getOperation()) {
            if ("PUT".equalsIgnoreCase(op.httpMethod) && !op.pathParams.isEmpty()) {
                op.vendorExtensions.put("x-terraform-is-update", true);
                break;
            }
        }

        return super.postProcessOperationsWithModels(objs, allModels);
    }

    /** {@code /organizations} -> {@code Organization}, which upstream reads as the resource name. */
    @Override
    public String toApiName(String name) {
        return camelize(singular(lastSegment(name)));
    }

    @Override
    public String toApiFilename(String name) {
        return underscore(toApiName(name));
    }

    /** A trailing {@code /{param}} is the member of a collection, not a collection. */
    private String collectionOf(String path) {
        int cut = path.lastIndexOf('/');

        if (cut > 0 && path.endsWith("}") && path.startsWith("{", cut + 1)) {
            return path.substring(0, cut);
        }
        return path;
    }

    private String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    // ponytail: a trailing "s" is the whole pluralisation rule. The paths this
    // generator is pointed at are organizations, tenants and applications; a
    // document spelling "addresses" or "people" wants a real inflector.
    private String singular(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.endsWith("s") && !lower.endsWith("ss")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }
}
