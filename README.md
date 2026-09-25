# terraform-provider-wso2

A Terraform provider for WSO2 Identity Server, generated from the API
descriptions WSO2 publishes with the server itself. It exists to create root
organizations (tenants), organizations under them, the applications within
those, and the connections (external identity providers) those applications
federate to.

```bash
nix develop
bin/generate        # four WSO2 documents in, this repo out
```

Everything under `internal/` is generated and **committed**: the Dockerfile
compiles what is in the tree, not what a regeneration would produce. Run
`bin/generate`, read the diff, commit it.

## Where it comes from

Four documents WSO2 publishes with the server itself — tenant management,
organization management, application management, identity provider (connection)
management — merged into one and read in a single pass. **73 resources and 73
data sources**, one per collection or singleton path in those documents. Nothing is filtered: generating less than
the document describes took extra code to arrange, and every path left out is a
thing nobody can manage.

```bash
nix develop
bin/generate        # four WSO2 documents in, this repo out
```

Some you will want first:

| resource | what |
|---|---|
| `wso2_tenant` | root organizations |
| `wso2_tenant_owner` | the owner account, including its password |
| `wso2_organization` | organizations under a tenant |
| `wso2_application` | applications |
| `wso2_application_inbound_protocol_oidc` | an application's OIDC configuration |
| `wso2_identity_provider` | connections — a federated IdP, its authenticators and its JIT provisioning |

A resource is named after its whole path, not its last segment.
`/organizations/{organization-id}/applications/{application-id}/share` and
`/applications/{applicationId}/share` both end in "share", and naming by the
last segment gave them the same filename — the second silently overwrote the
first, a resource that vanished with no error anywhere. So they are
`wso2_organization_application_share` and `wso2_application_share`.

Everything under `internal/` is generated and **committed**: the Dockerfile
compiles what is in the tree, not what a regeneration would produce. Run
`bin/generate`, read the diff, commit it.

Those documents live in [wso2/identity-api-server], cloned into `reference/`
along with [openapi-generator] itself. `reference/` is gitignored — it is
upstream, read only.

```bash
mkdir -p reference && cd reference
git clone --depth 1 --filter=blob:none https://github.com/wso2/identity-api-server.git
```

The four documents are separate APIs and openapi-generator reads one document
per run, so `bin/merge-specs` puts them together first. They disagree about
what `Error`, `Link`, `Attribute` and `Certificate` are, and more than one of
them spells a share operation with the same `operationId` — which makes a merged document *invalid*,
not merely ambiguous — so the merge renames per source rather than letting one
definition quietly win.

## The generator

`-g wso2-terraform` is upstream's `terraform-provider` generator with one hook
replaced, built by `nix build .#openapi-generator-wso2` — `javac` against the
packaged CLI's own jar and an SPI entry, no Maven and no checkout of the
generator.

Upstream keys its operation map on the **tag** and then asks
`CodegenOperation.isRestfulCreate()` and friends which operation is the create.
Those helpers strip `"/" + baseName` from the path, and WSO2 tags
`/organizations` with the singular `Organization` — the strip leaves `"s"`,
nothing looks RESTful, and every resource comes out with an empty schema and a
`Create` that refuses. A Terraform resource is a collection path plus its
member path, so that is what `generators/wso2` keys on, and upstream's
detection then works as written.

It also decides the update: where a member path offers both PUT and PATCH,
upstream takes whichever the document lists first. A PATCH body is a list of
patch operations while the resource sends a whole model, so the update is the
PUT — WSO2 lists PATCH first on organizations.

`Dockerfile`, `.dockerignore` and `.github/workflows/build-image.yml` are
generated too, from `generators/wso2/resources/terraform-provider/`. A provider
that cannot be deployed is not finished, and where this one is deployed is not
something a hand-written file should have to remember.

## How it is deployed

There is no provider registry involved. The image carries the binary and
exists only to be copied out of: `provider-opentofu` runs it as an
initContainer and copies the binary into a filesystem mirror that OpenTofu
resolves the provider from.

```
<mirror>/ghcr.io/n-at-han-k/wso2/<version>/linux_amd64/terraform-provider-wso2_v<version>
```

`ghcr.io` is where the image is published, not a registry that serves
providers; nothing is fetched over the network for it.

## What it does not do yet

- Nested objects become a `schema.StringAttribute` holding JSON — an
  application's `inboundProtocolConfiguration`, `authenticationSequence` and
  `claimConfiguration` all land that way.
- Arrays of objects become `ListAttribute{ElementType: types.StringType}`.
- Nothing is `Computed`, because the WSO2 documents never say `readOnly` — so
  `created`, `lastModified` and `version` are asked for in configuration.
- The tenant API is super-tenant scoped (`/api/server/v1/tenants`) while the
  organization API is tenant scoped (`/t/{tenant-domain}/api/server/v1`). The
  client holds one base URL, so those are two provider instances.
- Tenants have no member-path update or delete in this API. That is the
  document being honest, not a gap in the generator.

[wso2/identity-api-server]: https://github.com/wso2/identity-api-server
[openapi-generator]: https://github.com/openapitools/openapi-generator
