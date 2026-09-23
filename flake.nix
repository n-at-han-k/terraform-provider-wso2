{
  # The shell the generator and the provider share: openapi-generator writes
  # the Go, Go builds it, tofu runs it.
  description = "WSO2 Terraform provider, generated from the Identity Server API specs";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    utils.url = "github:numtide/flake-utils";
  };
  outputs = { self, nixpkgs, utils }:
    (utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # The one hook a template cannot reach: which operations are one
        # resource. javac against the CLI's own jar and an SPI entry -- no
        # Maven, no checkout of the generator.
        wso2-codegen = pkgs.stdenv.mkDerivation {
          name = "wso2-codegen";
          src = ./generators/wso2;

          nativeBuildInputs = [ pkgs.jdk ];

          buildPhase = ''
            mkdir -p classes
            javac -nowarn -proc:none \
              -cp ${pkgs.openapi-generator-cli}/share/java/openapi-generator-cli.jar \
              -d classes $(find src -name '*.java')
            cp -r resources/. classes/
            jar cf wso2-codegen.jar -C classes .
          '';

          installPhase = ''
            install -Dm644 wso2-codegen.jar $out/share/java/wso2-codegen.jar
          '';
        };

        # The packaged CLI runs `java -jar`, which ignores -cp; a generator on
        # the classpath needs the main class named.
        openapi-generator-wso2 = pkgs.writeShellApplication {
          name = "openapi-generator-wso2";
          runtimeInputs = [ pkgs.jre ];
          text = ''
            exec java -cp ${wso2-codegen}/share/java/wso2-codegen.jar:${pkgs.openapi-generator-cli}/share/java/openapi-generator-cli.jar \
              org.openapitools.codegen.OpenAPIGenerator "$@"
          '';
        };

      in
      {
        packages = { inherit wso2-codegen openapi-generator-wso2; };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            go
            gopls

            # The patched generator (`-g wso2-terraform`), which groups the
            # document's operations into resources.
            openapi-generator-wso2

            # And upstream's, unpatched, for looking at what stock
            # `-g terraform-provider` does with the same document. The npm
            # openapi-generator-cli is the same jar fetched at runtime, which a
            # flake cannot pin, so this is the packaged one instead.
            openapi-generator-cli

            # bin/merge-specs: three WSO2 documents, one provider binary.
            (python3.withPackages (ps: [ ps.pyyaml ]))

            # terraform itself is BUSL and unfree; tofu runs the same provider.
            opentofu
          ];

          # A Terraform provider is pure Go, and cgo only costs a C compiler.
          shellHook = ''
            export CGO_ENABLED=0
          '';
        };
      }));
}
