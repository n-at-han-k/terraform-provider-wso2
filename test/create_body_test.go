// Checks on what the generated client actually puts on the wire.
//
// It lives OUTSIDE internal/, because bin/generate clears internal/ on every
// run -- a check kept in there is deleted by the next regeneration, which is
// exactly when you want it.
package test

import (
	"encoding/json"
	"testing"

	"github.com/n-at-han-k/terraform-provider-wso2/internal/client"
)

// An unset nested object must be ABSENT from a create body, not an empty one.
// Go's `omitempty` does nothing for a struct, so these went out as
// "associatedRoles":{"allowedAudience":""} and WSO2 answered UE-10000,
// "provided request body content is not in the expected format".
func TestApplicationCreateBodyOmitsUnsetObjects(t *testing.T) {
	out := &client.ApplicationModel{}
	out.Name = "plaat.fm"
	out.Description = "The plaat.fm signup and login site."
	out.AccessUrl = "https://plaat.fm"

	inbound := `{"oidc":{"grantTypes":["authorization_code","refresh_token"],` +
		`"callbackURLs":["https://plaat.fm/auth/wso2/callback"],"publicClient":true,` +
		`"pkce":{"mandatory":true,"supportPlainTransformAlgorithm":false},"allowedOrigins":[]}}`
	if err := json.Unmarshal([]byte(inbound), &out.InboundProtocolConfiguration); err != nil {
		t.Fatalf("inbound: %v", err)
	}

	body, err := json.Marshal(out)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var got map[string]any
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("remarshal: %v", err)
	}

	for _, key := range []string{"associatedRoles", "claimConfiguration",
		"authenticationSequence", "advancedConfigurations", "provisioningConfigurations"} {
		if _, present := got[key]; present {
			t.Errorf("%s must be absent when unset; body was %s", key, body)
		}
	}

	oidc, ok := got["inboundProtocolConfiguration"].(map[string]any)["oidc"].(map[string]any)
	if !ok {
		t.Fatalf("oidc missing from body %s", body)
	}
	if _, present := oidc["fapiProfile"]; present {
		t.Errorf("fapiProfile must be absent when unset; body was %s", body)
	}

	t.Logf("body: %s", body)
}

// An explicit false must be SENT, not dropped. `omitempty` on a bool cannot
// tell false from unset, and WSO2 answers a missing
// supportPlainTransformAlgorithm with a 500, APP-65006 -- sending it is a 201.
// Verified against the server both ways.
func TestExplicitFalseSurvivesTheWire(t *testing.T) {
	out := &client.ApplicationModel{}
	out.Name = "plaat.fm"

	inbound := `{"oidc":{"publicClient":true,` +
		`"pkce":{"mandatory":true,"supportPlainTransformAlgorithm":false}}}`
	if err := json.Unmarshal([]byte(inbound), &out.InboundProtocolConfiguration); err != nil {
		t.Fatalf("inbound: %v", err)
	}

	body, err := json.Marshal(out)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var got map[string]any
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("remarshal: %v", err)
	}

	pkce := got["inboundProtocolConfiguration"].(map[string]any)["oidc"].(map[string]any)["pkce"].(map[string]any)
	if _, present := pkce["supportPlainTransformAlgorithm"]; !present {
		t.Errorf("an explicit false must be sent; body was %s", body)
	}
}
