default: testacc

# Run acceptance tests
.PHONY: testacc
testacc:
	TF_ACC=1 go test ./... -v $(TESTARGS) -timeout 120m

# Build provider
.PHONY: build
build:
	go build -o terraform-provider-wso2

# Install provider locally
.PHONY: install
install: build
	mkdir -p ~/.terraform.d/plugins/ghcr.io/n-at-han-k/wso2/1.0.0/$(shell go env GOOS)_$(shell go env GOARCH)
	mv terraform-provider-wso2 ~/.terraform.d/plugins/ghcr.io/n-at-han-k/wso2/1.0.0/$(shell go env GOOS)_$(shell go env GOARCH)/

# Generate documentation
.PHONY: docs
docs:
	go generate ./...

# Run linter
.PHONY: lint
lint:
	golangci-lint run ./...
