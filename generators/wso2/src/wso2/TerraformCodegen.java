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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern PARAM = Pattern.compile("\\{([^{}/]+)\\}");

    public TerraformCodegen() {
        super();
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
     * Which operation is the create, the read, the update, the delete.
     *
     * Upstream asks {@code CodegenOperation.isRestfulCreate()} and friends,
     * and those cannot answer for a NESTED resource: {@code isMemberPath()}
     * opens with {@code if (pathParams.size() != 1) return false}, so
     * {@code /tenants/{tenant-id}/owners/{owner-id}} -- two path params --
     * looks like nothing at all, and the whole resource comes out empty.
     *
     * The shape of the path already says it. This group IS a collection path
     * and its member path, so an operation on the collection is the create or
     * the list, and one on the member is the read, the update or the delete.
     * Marked as vendor extensions, which is upstream's own first-pass hook.
     *
     * Where a member path offers both PUT and PATCH the update is the PUT: a
     * PATCH body is a list of patch operations while the resource sends a
     * whole model.
     */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        List<CodegenOperation> group = objs.getOperations().getOperation();
        String collection = group.isEmpty() ? "" : collectionOf(group.get(0).path);

        // A SINGLETON: one path, no member beneath it.
        // /applications/{applicationId}/inbound-protocols/oidc is GET, PUT and
        // DELETE on a fixed path -- the resource IS that path, there is no
        // collection to list. Reading its GET as a list left fourteen resources
        // here with no CRUD at all.
        boolean singleton = group.stream().noneMatch(op -> isMember(collection, op.path));

        CodegenOperation update = null;

        for (CodegenOperation op : group) {
            boolean own = singleton || !collection.equals(op.path);
            String method = op.httpMethod.toUpperCase(Locale.ROOT);

            if (!own && "POST".equals(method)) {
                op.vendorExtensions.put("x-terraform-is-create", true);
            } else if (!own && "GET".equals(method)) {
                op.vendorExtensions.put("x-terraform-is-list", true);
            } else if (own && (singleton || isMember(collection, op.path))) {
                if ("GET".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-read", true);
                } else if ("DELETE".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-delete", true);
                } else if ("POST".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-create", true);
                } else if ("PUT".equals(method) || ("PATCH".equals(method) && update == null)) {
                    update = "PUT".equals(method) || update == null ? op : update;
                }
            }
        }

        if (update != null) {
            update.vendorExtensions.put("x-terraform-is-update", true);
        }

        OperationsMap processed = super.postProcessOperationsWithModels(objs, allModels);

        // Upstream takes the request body from the CREATE operation only, so a
        // resource you can update but not create -- a tenant's owner is PUT,
        // never POSTed -- had no request model, and ToClientModel came out as
        // `*client.` with no type. The update body is the write shape there.
        if (processed.getOperations().get("requestModel") == null) {
            CodegenOperation writes = operationFlagged(group, "x-terraform-is-update");

            if (writes != null && writes.bodyParam != null) {
                processed.getOperations().put("requestModel", writes.bodyParam.dataType);
            }
        }

        // A free-form or list body is not a model. GET /applications/{id}/export
        // answers an unconstrained object -- returnType `interface{}` -- and
        // PATCH /organizations/self takes `[]OrganizationPatchRequestItem`, so
        // the templates spelled `client.interface{}` and `client.[]Organization...`.
        // Where the name is not a generated model, there is no model. AFTER the
        // fallback above, or the fallback puts one straight back.
        for (String key : new String[] { "responseModel", "requestModel" }) {
            Object name = processed.getOperations().get(key);

            if (name != null && modelNamed(allModels, String.valueOf(name)) == null) {
                processed.getOperations().put(key, null);
            }
        }

        // Upstream strips a trailing "api" off the resource name, which is right
        // for a tag called PetApi and wrong for a PATH segment: /authorized-apis
        // became wso2_application_authorized -- a name that says nothing, and
        // one the file beside it (application_authorized_api_resource.go) does
        // not even agree with. The path already named this resource.
        processed.getOperations().put("resourceClassName", toApiName(collection));
        processed.getOperations().put("resourceName",
                underscore(toApiName(collection)).toLowerCase(Locale.ROOT));

        reshapeAttributes(processed.getOperations(), allModels);
        wirePathParams(processed.getOperations(), group);

        // Whether the identifier is a string, which decides how a template can
        // ask whether it is empty.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tf =
                (List<Map<String, Object>>) processed.getOperations().get("tfAttributes");
        Object idName = processed.getOperations().get("idFieldExported");
        processed.getOperations().put("hasIdAttribute", tf != null
                && tf.stream().anyMatch(a -> String.valueOf(idName).equals(a.get("goName"))));

        // Exactly which imports the model file needs. Upstream puts its import
        // block inside {{#responseModel}}, so a resource with no response
        // model got a struct and no imports at all -- and an import Go does
        // not need is as fatal as one it does.
        boolean usesTypes = tf != null && tf.stream()
                .anyMatch(a -> String.valueOf(a.get("terraformType")).startsWith("types."));
        boolean usesJson = tf != null && tf.stream()
                .anyMatch(a -> Boolean.TRUE.equals(a.get("isJson")));
        boolean request = processed.getOperations().get("requestModel") != null;
        boolean response = processed.getOperations().get("responseModel") != null;

        // json and fmt are needed only where a conversion actually uses them:
        // ToClientModel parses the JSON attributes the request carries,
        // FromClientModel renders the ones the response answers. An import Go
        // does not need is as fatal as one it does.
        boolean toJson = request && tf != null && tf.stream().anyMatch(a ->
                Boolean.TRUE.equals(a.get("isJson")) && Boolean.TRUE.equals(a.get("inRequest")));
        boolean fromJson = response && tf != null && tf.stream().anyMatch(a ->
                Boolean.TRUE.equals(a.get("isJson")) && Boolean.TRUE.equals(a.get("readBack")));

        processed.getOperations().put("usesTypes", usesTypes);
        processed.getOperations().put("usesJsontypes", usesJson);
        processed.getOperations().put("usesEncodingJson", toJson || fromJson);
        processed.getOperations().put("usesFmt", toJson);
        processed.getOperations().put("hasClientModel", request || response);

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

        // Upstream builds this list only when there is a response model, so a
        // create-only endpoint -- POST /channel-verified-tenants answers 201
        // and nothing -- got no schema at all, and an empty model struct. The
        // create body is a schema.
        if (attributes == null) {
            attributes = new ArrayList<>();
            operations.put("tfAttributes", attributes);
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

            unpoint(attribute);
            retype(attribute);
        }

        // What the create body takes and no response ever answers.
        for (Map.Entry<String, CodegenProperty> entry : writable.entrySet()) {
            if (answered.contains(entry.getKey())) {
                continue;
            }
            attributes.add(writeOnlyAttribute(entry.getValue()));
        }

        for (Map<String, Object> attribute : attributes) {
            String terraformName = String.valueOf(attribute.get("terraformName"));

            if (RESERVED.contains(terraformName)) {
                // The Go field and the JSON key are untouched; only the name
                // configuration spells it moves out of Terraform's way.
                attribute.put("terraformName", "api_" + terraformName);
            }
        }

        boolean anyJson = attributes.stream()
                .anyMatch(attribute -> Boolean.TRUE.equals(attribute.get("isJson")));

        // Nothing is a types.List any more, so the import that served them is
        // not needed and the JSON one is.
        operations.put("hasListAttributes", false);
        operations.put("hasJsonAttributes", anyJson);
    }


    /**
     * A nested resource hangs off its parents, and their identifiers are in
     * the path, not in any response body.
     *
     * {@code /tenants/{tenant-id}/owners/{owner-id}} needs the tenant id to
     * address an owner at all, so it becomes a Required attribute of the
     * resource -- nothing else can supply it. Upstream's templates interpolate
     * exactly ONE argument into the path format, which is why a nested path
     * came out as {@code %!v(MISSING)}; here each operation carries the whole
     * ordered argument list, receiver included, so the template just spreads
     * it.
     */
    private void wirePathParams(OperationMap operations, List<CodegenOperation> group) {
        // Whichever operation spells the longest path addresses the resource:
        // the member path in a collection, or the single path of a singleton.
        // Asking only the read (or the delete) left
        // /tenants/{tenant-id}/lifecycle-status -- a PUT and nothing else --
        // with no path arguments at all.
        CodegenOperation addressing = group.stream()
                .max((a, b) -> Integer.compare(a.path.length(), b.path.length()))
                .orElse(null);

        if (addressing == null) {
            return;
        }

        List<String> names = paramsOf(addressing.path);

        if (names.isEmpty()) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributes =
                (List<Map<String, Object>>) operations.get("tfAttributes");

        // No response model means no attribute list to add parents to -- but
        // the path arguments below are still what addresses the resource.
        if (attributes == null) {
            attributes = new ArrayList<>();
            operations.put("tfAttributes", attributes);
        }

        // Every param but the last: the last one IS this resource's own id,
        // which upstream already resolved against the response model.
        for (String name : names.subList(0, names.size() - 1)) {
            String terraformName = underscore(name.replace('-', '_')).toLowerCase(Locale.ROOT);

            if (attributes.stream().anyMatch(a -> terraformName.equals(a.get("terraformName")))) {
                continue;
            }

            Map<String, Object> parent = new HashMap<>();
            parent.put("name", name);
            parent.put("terraformName", terraformName);
            parent.put("goName", camelize(terraformName));
            parent.put("goType", "string");
            parent.put("terraformType", "types.String");
            parent.put("terraformAttrType", "schema.StringAttribute");
            parent.put("isString", true);
            parent.put("isRequired", true);
            parent.put("isOptional", false);
            parent.put("isComputed", false);
            parent.put("isSensitive", false);
            // It addresses the resource; it is not part of any body, and no
            // response answers it.
            parent.put("inRequest", false);
            parent.put("readBack", false);
            parent.put("description", "Identifier of the parent " + name.replace("-id", "") + ".");
            attributes.add(parent);
        }

        // The resource's OWN identifier, when no response body carries it:
        // /applications/loginflow/status/{operation_id} is addressed by a value
        // that only the caller knows, and upstream looked for it among the
        // response model's properties and found nothing.
        final String guessedId = String.valueOf(operations.get("idFieldExported"));
        boolean creatable = operationFlagged(group, "x-terraform-is-create") != null;

        if (attributes.stream().noneMatch(a -> guessedId.equals(a.get("goName")))) {
            Map<String, Object> id = new HashMap<>();
            String last = names.get(names.size() - 1);
            String terraformName = underscore(last.replace('-', '_')).toLowerCase(Locale.ROOT);

            // Upstream guessed "id" off the read response and there is none, so
            // the identifier is what the path actually calls it.
            String ownId = camelize(terraformName);
            operations.put("idFieldExported", ownId);
            operations.put("idFieldTerraformName", terraformName);
            operations.put("idFieldValueAccessor", ".ValueString()");

            id.put("name", last);
            id.put("terraformName", terraformName);
            id.put("goName", ownId);
            id.put("goType", "string");
            id.put("terraformType", "types.String");
            id.put("terraformAttrType", "schema.StringAttribute");
            id.put("isString", true);
            // With no create there is nobody to assign it, so it is asked for.
            id.put("isRequired", !creatable);
            id.put("isOptional", creatable);
            id.put("isComputed", creatable);
            id.put("isSensitive", false);
            id.put("inRequest", false);
            id.put("readBack", false);
            id.put("description", "Identifier of the " + last.replace("-id", "") + ".");
            attributes.add(id);
        }

        // One accessor per param name, then each operation spreads the params
        // ITS OWN path spells. Slicing the last one off as "the id" was wrong
        // for a singleton, whose path is the whole address --
        // .../oidc/revoke got zero arguments for one %v.
        Map<String, String> accessors = new LinkedHashMap<>();

        for (String name : names) {
            boolean own = name.equals(names.get(names.size() - 1));
            String goName = own
                    ? String.valueOf(operations.get("idFieldExported"))
                    : camelize(underscore(name.replace('-', '_')).toLowerCase(Locale.ROOT));
            String accessor = own
                    ? String.valueOf(operations.get("idFieldValueAccessor"))
                    : ".ValueString()";
            accessors.put(name, goName + accessor);
        }

        argsOf(operations, group, "x-terraform-is-create", "createArgs", "plan", accessors);
        argsOf(operations, group, "x-terraform-is-read", "readArgs", "state", accessors);
        argsOf(operations, group, "x-terraform-is-read", "configArgs", "config", accessors);
        // The create reads the new resource back, and there it is `plan` that
        // holds the identifier the Location header just supplied.
        argsOf(operations, group, "x-terraform-is-read", "readArgsPlan", "plan", accessors);
        // From STATE, not the plan: an identifier cannot change on an update,
        // and the plan's copy of a Computed one is unknown.
        argsOf(operations, group, "x-terraform-is-update", "updateArgs", "state", accessors);
        argsOf(operations, group, "x-terraform-is-delete", "deleteArgs", "state", accessors);

        // A nested resource cannot be imported by its own id alone: nothing
        // else tells Terraform which tenant an owner belongs to, and the
        // update would then PUT to /tenants//owners/<id>.
        List<Map<String, Object>> parts = new ArrayList<>();
        StringBuilder hint = new StringBuilder();

        for (int i = 0; i < names.size(); i++) {
            // The last one is this resource's OWN identifier, and the schema
            // may well call it something other than the path does: the owner
            // of /tenants/{tenant-id}/owners/{owner-id} has an `id` property,
            // so the attribute is `id` and setting `owner_id` on import fails.
            String terraformName = i == names.size() - 1
                    ? String.valueOf(operations.get("idFieldTerraformName"))
                    : underscore(names.get(i).replace('-', '_')).toLowerCase(Locale.ROOT);
            Map<String, Object> part = new HashMap<>();

            part.put("terraformName", terraformName);
            part.put("index", i);
            parts.add(part);

            if (hint.length() > 0) {
                hint.append('/');
            }
            hint.append('<').append(terraformName).append('>');
        }

        if (names.size() > 1) {
            operations.put("importParts", parts);
            operations.put("importPartCount", names.size());
            operations.put("importHint", hint.toString());
        }

        CodegenOperation create = operationFlagged(group, "x-terraform-is-create");
        if (create != null) {
            operations.put("createPath",
                    create.vendorExtensions.getOrDefault("x-terraform-path-fmt", create.path));
            operations.put("createHasPathParams", !paramsOf(create.path).isEmpty());
        }
    }

    /** The arguments one operation's own path needs, receiver baked in. */
    private void argsOf(OperationMap operations, List<CodegenOperation> group, String flag,
                        String key, String receiver, Map<String, String> accessors) {
        CodegenOperation op = operationFlagged(group, flag);

        if (op == null) {
            return;
        }

        List<Map<String, Object>> args = new ArrayList<>();

        for (String name : paramsOf(op.path)) {
            String accessor = accessors.get(name);

            if (accessor == null) {
                continue;
            }
            Map<String, Object> arg = new HashMap<>();
            arg.put("expr", receiver + "." + accessor);
            args.add(arg);
        }

        operations.put(key, args);
    }

    private CodegenOperation operationFlagged(List<CodegenOperation> group, String flag) {
        return group.stream()
                .filter(op -> Boolean.TRUE.equals(op.vendorExtensions.get(flag)))
                .findFirst()
                .orElse(null);
    }

    /** The {param} names of a path, in the order the path spells them. */
    private List<String> paramsOf(String path) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PARAM.matcher(path);

        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        return names;
    }

    /**
     * A pointer to a scalar is still that scalar.
     *
     * Upstream decides an attribute's Terraform type by matching the Go type
     * name, so making bools pointers -- which is what sends an explicit false
     * -- turned every one of them into a types.String that no conversion
     * branch touched, and the attribute was simply never assigned: "provider
     * still indicated an unknown value for application_enabled".
     */
    private void unpoint(Map<String, Object> attribute) {
        String goType = String.valueOf(attribute.get("goType"));

        if (!goType.startsWith("*")) {
            return;
        }

        String pointed = goType.substring(1);
        boolean isBool = "bool".equals(pointed);
        boolean isInt = "int".equals(pointed) || "int32".equals(pointed) || "int64".equals(pointed);
        boolean isFloat = "float32".equals(pointed) || "float64".equals(pointed);
        boolean isString = "string".equals(pointed);

        if (!isBool && !isInt && !isFloat && !isString) {
            return;
        }

        attribute.put("isBool", isBool);
        attribute.put("isInt64", isInt);
        attribute.put("isFloat64", isFloat);
        attribute.put("isString", isString);
        attribute.put("terraformType", goType(pointed));
        attribute.put("terraformAttrType", goAttrType(pointed));
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

        unpoint(attribute);
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
                // A nested object is a POINTER, because Go's `omitempty` does
                // nothing for a struct: an unset one still goes out as
                // "associatedRoles":{"allowedAudience":""}, and WSO2 answers
                // UE-10000, "provided request body content is not in the
                // expected format" -- the empty string is not one of the
                // audiences its enum allows. A nil pointer is simply absent.
                // complexType, not isModel: a property written as
                // `allOf: [$ref: FapiProfile]` -- which is how this document
                // attaches a description to a ref -- is not flagged a model,
                // and went out as "fapiProfile":{} regardless.
                // A BOOL IS A POINTER TOO, for the same reason and a worse
                // consequence: `omitempty` cannot tell false from unset, so
                // `supportPlainTransformAlgorithm = false` was dropped from
                // the body -- and WSO2 answers a MISSING one with a 500,
                // APP-65006 "server encountered an unexpected error". Sending
                // it explicitly is a 201. Verified against the server both
                // ways.
                if ("bool".equals(property.dataType)) {
                    property.dataType = "*bool";
                }

                if ((property.isModel || property.complexType != null)
                        && !property.isArray && !property.isMap
                        && !property.dataType.startsWith("*")) {
                    property.dataType = "*" + property.dataType;
                }

                property.vendorExtensions.put("x-go-datatag",
                        " `json:\"" + property.baseName + ",omitempty\"`");
            }
        }

        return processed;
    }

    /**
     * The whole path names the resource, not just its last segment.
     *
     * {@code /organizations/{organization-id}/applications/{application-id}/share}
     * and {@code /applications/{applicationId}/share} both end in "share", so
     * naming by the last segment gave both the same file and the second one
     * SILENTLY overwrote the first -- a resource that vanished with no error
     * anywhere. Every literal segment, singularised, is unambiguous:
     * organization_application_share and application_share.
     *
     * {@code /tenants/{tenant-id}/owners} -> {@code TenantOwner}.
     */
    @Override
    public String toApiName(String name) {
        StringBuilder parts = new StringBuilder();

        String[] segments = trim(name).split("/");

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            if (segment.isEmpty() || segment.startsWith("{")) {
                continue;
            }

            // Singular means "one of these". A literal followed by a parameter
            // names one -- /applications/{applicationId}/share is a share of ONE
            // application -- while /applications/share acts on the collection.
            // Both end in "share", and without this they were one name, one
            // file, and one of the two resources silently gone.
            boolean names1 = i == segments.length - 1
                    || (i + 1 < segments.length && segments[i + 1].startsWith("{"));
            String spelled = names1 ? singular(segment) : segment;

            if (parts.length() > 0) {
                parts.append('_');
            }
            parts.append(underscore(spelled.replace('-', '_')).toLowerCase(Locale.ROOT));
        }

        return camelize(parts.toString());
    }

    private String trim(String path) {
        String trimmed = path;

        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }

    @Override
    public String toApiFilename(String name) {
        return underscore(toApiName(name));
    }

    /** A trailing {@code /{param}} is the member of a collection, not a collection. */
    private String collectionOf(String raw) {
        // /applications/{applicationId}/inbound-protocols/ and
        // /applications/{applicationId}/inbound-protocols are the same
        // collection, and the document spells both.
        String path = raw.length() > 1 && raw.endsWith("/")
                ? raw.substring(0, raw.length() - 1)
                : raw;
        int cut = path.lastIndexOf('/');

        if (cut > 0 && path.endsWith("}") && path.startsWith("{", cut + 1)) {
            return path.substring(0, cut);
        }
        return path;
    }

    /** The member of THIS collection: its path plus one trailing {param}. */
    private boolean isMember(String collection, String path) {
        return collectionOf(path).equals(collection) && !path.equals(collection);
    }

    private String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /**
     * A trailing "s" is not always a plural: "lifecycle-status" became
     * "lifecycle_statu" and "passive-sts" became "passive_st". Latin endings
     * and that one acronym are left alone; "tenants" and "secrets" are still
     * plurals and still lose it.
     *
     * ponytail: not an inflector. A document saying "addresses" or "people"
     * wants a real one.
     */
    /**
     * Terraform reserves these at the root of a resource block, and a schema
     * using one is refused outright: "count is a reserved root attribute/block
     * name". WSO2's list responses carry a `count`.
     */
    private static final List<String> RESERVED =
            Arrays.asList("count", "for_each", "depends_on", "provider", "lifecycle", "id_");

    private static final List<String> KEEP = Arrays.asList("ss", "us", "os", "sts");

    private String singular(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (!lower.endsWith("s") || KEEP.stream().anyMatch(lower::endsWith)) {
            return name;
        }
        return name.substring(0, name.length() - 1);
    }
}
