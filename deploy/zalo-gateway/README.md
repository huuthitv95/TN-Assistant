# TN Zalo-only egress gateway

This is a standalone mTLS CONNECT gateway for a Vietnam-hosted machine managed by TN. It has no open-proxy mode, no user-selected destinations and no rotating public proxy pool. It permits only `oauth.zaloapp.com:443` and `graph.zalo.me:443`.

The gateway does not terminate the inner TLS session to Zalo. The backend must continue validating Zalo's public certificate and hostname independently. The gateway authenticates its backend client with TLS, verifies that client's exact SHA-256 certificate fingerprint, resolves the configured Zalo hostname, rejects non-public and special-use destination addresses, then dials the validated address directly from the gateway host.

## Configuration

The service reads the environment variables in [`zalo-gateway.env.example`](zalo-gateway.env.example). Install the completed file as `/etc/tn-assistant/zalo-gateway.env`, owned by `root:tnzalogateway` with mode `0640`. Replace every placeholder with an issued value; do not copy placeholder fingerprints into production.

Install a gateway server certificate/key and a dedicated CA used only to issue the backend's gateway client certificate. Keep the server private key readable only by the service group. The gateway trusts the client CA and then requires an exact certificate fingerprint from `TN_ZALO_GATEWAY_ALLOWED_CLIENT_CERT_SHA256`; during planned rotation, list both old and new fingerprints for the short overlap window.

The `expected_country: VN` entry in the architecture YAML is an operator requirement, not an automatic geolocation assertion in this program. Use a Vietnam-hosted address assigned to TN (or contractually assigned to it), and verify that Zalo accepts the production egress before the OAuth route is considered working. Do not put IP addresses from public/free-proxy lists here.

## Build and install

From this directory, run the package checks and create a Linux AMD64 binary:

```sh
go test ./...
go vet ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o dist/tn-zalo-gateway .
```

Install the binary at `/srv/tn-zalo-gateway/zalo-gateway`, install [`tn-zalo-gateway.service`](tn-zalo-gateway.service), create the `tnzalogateway` system user/group, and place certificates/config under `/etc/tn-assistant/gateway` and `/etc/tn-assistant/zalo-gateway.env`. Grant only `CAP_NET_BIND_SERVICE` so the unprivileged service can bind port 443. Firewall ingress should be limited to the backend's known source ranges where stable ranges are available; mTLS and the exact certificate fingerprint remain mandatory regardless.

`GET /_tn/health` is available only to the authorized mTLS client and reports process health. It does not prove Vietnamese geolocation or Zalo acceptance. Request logs contain the allowlisted host, outcome, duration and byte counts only; they omit paths, headers, OAuth data and payloads. Unlisted hosts, literal IP destinations, non-443 ports, non-CONNECT requests and unauthenticated clients fail closed.

## Current status

Source, environment example and systemd unit are prepared locally. No server, certificate, DNS name, gateway secret or public IP has been created or deployed. Production acceptance still requires deployment to the authorized Vietnam host, transport verification from the backend, a real owner-confirmed Zalo flow and monitoring.
