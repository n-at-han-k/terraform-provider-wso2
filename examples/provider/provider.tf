terraform {
  required_providers {
    wso2 = {
      source = "ghcr.io/n-at-han-k/wso2"
    }
  }
}

provider "wso2" {
  endpoint = "https://localhost:9443/api/server/v1"
  # api_key  = "your-api-key"
  # token    = "your-bearer-token"
}
