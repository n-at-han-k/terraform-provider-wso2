package wso2;

import io.swagger.v3.oas.models.Operation;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.TerraformProviderCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

        OperationsMap processed = super.postProcessOperationsWithModels(objs, allModels);

        reshapeAttributes(processed.getOperations(), allModels);

        // Whether the identifier is a string, which decides how a template can
        // ask whether it is empty.
        processed.getOperations().put("idIsString",
                ".ValueString()".equals(processed.getOperations().get("idFieldValueAccessor")));

        return processed;
    }

    /**
     * Upstream builds the resource schema out of the READ response model, and
     * then sends that same model back as the create body. Three things go
     * wrong, and all three are fatal to actually declaring a resource:
     *
     * The response model says every property the server always answers is
     * `required` and nothing is readOnly unless the document says so -- and
     * the WSO2 documents never say so. `wso2_organization` came out demanding
     * `id`, `status`, `version`, `created` and `last_modified` in
     * configuration, values the server assigns.
     *
     * A property the create body takes and the response model does not have is
     * missing from the schema entirely. A tenant's owner carries a `password`,
     * which no response ever echoes back, so there was no way to spell the one
     * field a tenant cannot be created without.
     *
     * And anything that is not a scalar is declared -- an object as a string,
     * a list as `ListAttribute{ElementType: types.StringType}` -- and then
     * converted NEITHER way. The field reached the schema and the model struct
     * and was silently never sent or read, which is where an organization's
     * `attributes` and a tenant's `owners` went.
     *
     * So the create request body is what decides what a person may write, the
     * response decides what is computed, and the schema is the union. Anything
     * not a scalar becomes a JSON string, which the templates do convert.
     */
    private void reshapeAttributes(OperationMap operations, List<ModelMap> allModels) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributes =
                (List<Map<String, Object>>) operations.get("tfAttributes");

        if (attributes == null) {
            return;
        }

        CodegenModel request = modelNamed(allModels, (String) operations.get("requestModel"));
        Map<String, CodegenProperty> writable = new LinkedHashMap<>();

        if (request != null) {
            for (CodegenProperty property : request.vars) {
                writable.put(property.baseName.toLowerCase(Locale.ROOT), property);
            }
        }

        Set<String> answered = new HashSet<>();

        for (Map<String, Object> attribute : attributes) {
            String name = String.valueOf(attribute.get("name")).toLowerCase(Locale.ROOT);
            answered.add(name);
            CodegenProperty writes = writable.get(name);

            // With no create operation there is nothing to infer from, and
            // upstream's answer stands.
            if (request != null) {
                // Optional AND Computed where the create body takes it without
                // insisting and the server answers it anyway -- a tenant's
                // `name` is not required and comes back as the domain. Optional
                // alone plans null and then the read answers a value, which is
                // "Provider produced inconsistent result after apply".
                attribute.put("isRequired", writes != null && writes.required);
                attribute.put("isOptional", writes != null && !writes.required);
                attribute.put("isComputed", writes == null || !writes.required);
            }

            // The server answers this one, so state can be refreshed from it --
            // unless the create body spells it differently, as a tenant's
            // owners are `Owner` going out (with a password) and `OwnerResponse`
            // coming back (without). Reading that back would drop the password
            // out of state and diff forever.
            boolean sameShape = writes == null
                    || writes.dataType.equals(String.valueOf(attribute.get("goType")));
            attribute.put("inRequest", writes != null);
            attribute.put("readBack", sameShape);

            retype(attribute);
        }

        // What the create body takes and no response ever answers.
        for (Map.Entry<String, CodegenProperty> entry : writable.entrySet()) {
            if (answered.contains(entry.getKey())) {
                continue;
            }
            attributes.add(writeOnlyAttribute(entry.getValue()));
        }

        boolean anyJson = attributes.stream()
                .anyMatch(attribute -> Boolean.TRUE.equals(attribute.get("isJson")));

        // Nothing is a types.List any more, so the import that served them is
        // not needed and the JSON one is.
        operations.put("hasListAttributes", false);
        operations.put("hasJsonAttributes", anyJson);
    }

    /** A list or an object travels as JSON; the templates convert those. */
    private void retype(Map<String, Object> attribute) {
        if (!Boolean.TRUE.equals(attribute.get("isList"))
                && !Boolean.TRUE.equals(attribute.get("isObject"))) {
            return;
        }

        attribute.put("isList", false);
        attribute.put("isObject", false);
        attribute.put("isJson", true);
        attribute.put("terraformType", "jsontypes.Normalized");
        attribute.put("terraformAttrType", "schema.StringAttribute");
    }

    private Map<String, Object> writeOnlyAttribute(CodegenProperty property) {
        Map<String, Object> attribute = new HashMap<>();

        attribute.put("name", property.baseName);
        attribute.put("terraformName", underscore(property.baseName).toLowerCase(Locale.ROOT));
        attribute.put("goName", camelize(property.baseName));
        attribute.put("goType", property.dataType);
        attribute.put("description", property.description != null ? property.description : "");
        attribute.put("isRequired", property.required);
        attribute.put("isOptional", !property.required);
        attribute.put("isComputed", false);
        attribute.put("inRequest", true);
        // Nothing answers it, so there is nothing to read back.
        attribute.put("readBack", false);
        attribute.put("isString", "string".equals(property.dataType));
        attribute.put("isInt64", "int64".equals(property.dataType) || "int32".equals(property.dataType));
        attribute.put("isFloat64", "float64".equals(property.dataType) || "float32".equals(property.dataType));
        attribute.put("isBool", "bool".equals(property.dataType));
        attribute.put("isList", property.isArray);
        attribute.put("isObject", property.isModel && !property.isArray);
        // ponytail: a name-based guess at what is secret. A document that says
        // writeOnly or x-terraform-sensitive is believed first; this catches the
        // ones that say neither, and WSO2's tenant owner password says neither.
        attribute.put("isSensitive", property.isWriteOnly
                || property.baseName.toLowerCase(Locale.ROOT).contains("password")
                || property.baseName.toLowerCase(Locale.ROOT).contains("secret"));
        attribute.put("terraformType", goType(property.dataType));
        attribute.put("terraformAttrType", goAttrType(property.dataType));

        retype(attribute);

        return attribute;
    }

    private String goType(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "int32": case "int64": case "int": return "types.Int64";
            case "float32": case "float64": return "types.Float64";
            case "bool": return "types.Bool";
            default: return "types.String";
        }
    }

    private String goAttrType(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "int32": case "int64": case "int": return "schema.Int64Attribute";
            case "float32": case "float64": return "schema.Float64Attribute";
            case "bool": return "schema.BoolAttribute";
            default: return "schema.StringAttribute";
        }
    }

    private CodegenModel modelNamed(List<ModelMap> allModels, String classname) {
        if (classname == null) {
            return null;
        }
        for (ModelMap map : allModels) {
            if (classname.equals(map.getModel().classname)) {
                return map.getModel();
            }
        }
        return null;
    }

    /**
     * Every field omitempty, because the resource sends the RESPONSE model as a
     * create body. A property the server assigns is Computed and therefore null
     * in the plan, which reaches Go as a zero value -- and without omitempty
     * that goes out as `"status": ""`, a field the create endpoint never asked
     * for and can refuse over.
     */
    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        ModelsMap processed = super.postProcessModels(objs);

        for (ModelMap map : processed.getModels()) {
            for (CodegenProperty property : map.getModel().vars) {
                property.vendorExtensions.put("x-go-datatag",
                        " `json:\"" + property.baseName + ",omitempty\"`");
            }
        }

        return processed;
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
