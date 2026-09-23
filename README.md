# terraform-provider-wso2

A Terraform provider for WSO2 Identity Server, generated from the API
descriptions WSO2 publishes with the server itself. It exists to create root
organizations (tenants), organizations under them, and applications within
those.

```bash
nix develop
bin/generate        # three WSO2 documents in, this repo out
```

Everything under `internal/` is generated and **committed**: the Dockerfile
compiles what is in the tree, not what a regeneration would produce. Run
`bin/generate`, read the diff, commit it.

## Where it comes from

| document | resource |
|---|---|
| `tenant-management.yaml` | `wso2_tenant` — root organizations |
| `org.wso2.carbon.identity.organization.management.yaml` | `wso2_organization` |
| `applications.yaml` | `wso2_application` |

Those live in [wso2/identity-api-server], cloned into `reference/` along with
[openapi-generator] itself. `reference/` is gitignored — it is upstream, read
only, and nothing here is built from a copy of it that this repo keeps.

```bash
mkdir -p reference && cd reference
git clone --depth 1 --filter=blob:none https://github.com/wso2/identity-api-server.git
```

The three documents are separate APIs and openapi-generator reads one document
per run, so `bin/merge-specs` puts them together first. They disagree about
what `Error`, `Link` and `Attribute` are, and two of them spell a share
operation with the same `operationId`, so the merge renames per source rather
than letting one definition quietly win.

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
