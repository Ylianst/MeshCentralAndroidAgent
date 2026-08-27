# Tunnel Authentication

This document explains how the MeshCentral Android Agent authenticates the
server and how relay **tunnels** (remote desktop, files, and file transfer) are
trusted. Tunnels do not run their own cryptographic handshake; instead they
inherit their trust from the already-authenticated agent control channel and
from TLS certificate pinning.

## Two Kinds of Connections

The agent opens two categories of WebSocket connections to the MeshCentral
server:

| Connection | Endpoint | Purpose |
| --- | --- | --- |
| Control channel | `wss://<server>/agent.ashx` | Long-lived, mutually authenticated command channel. |
| Relay tunnel | `wss://<server>/meshrelay.ashx?...` | Short-lived session for one remote desktop, files, or file-transfer operation. |

The control channel is where the real authentication happens. Every tunnel is
requested over that authenticated channel, so a tunnel is only as trustworthy as
the control channel that spawned it.

Relevant code:
- Control channel: [MeshAgent.kt](../app/src/main/java/com/meshcentral/agent/MeshAgent.kt)
- Relay tunnel: [MeshTunnel.kt](../app/src/main/java/com/meshcentral/agent/MeshTunnel.kt)

## Pairing Identity

Before any connection, the device is paired with a server using an `mc://`
pairing link (scanned by QR code, opened as a deep link, or typed manually):

```
mc://<server-host>,<server-identity-hash>,<device-group-id>
```

- `<server-host>` is the host the agent connects to.
- `<server-identity-hash>` is the expected hash of the server's agent
  certificate public key. The agent stores it as `serverCertHash` and uses it to
  verify the server during the handshake.
- `<device-group-id>` is the mesh/device group the device enrolls into.

The agent also generates its own 2048-bit RSA key pair and a self-signed X.509
certificate at first run. This is the device identity used to sign the
handshake.

## Control Channel Handshake

The control channel handshake is a mutual challenge/response that binds the
application-layer identities to the underlying TLS session. It runs over four
binary commands.

### 1. Record the TLS certificate

The agent connects with a custom `TrustManager` that does not reject the
server's TLS certificate, but records the SHA-384 hash of it into
`serverTlsCertHash`. Hostname verification is also bypassed. Transport security
therefore does **not** come from a public CA; it comes from pinning this hash
during the application handshake and again on every tunnel (see below).

See `getUnsafeOkHttpClient()` in
[MeshAgent.kt](../app/src/main/java/com/meshcentral/agent/MeshAgent.kt).

### 2. Agent sends nonce (command 1)

On connection open, the agent generates a 48-byte random `nonce` and sends:

```
[0x00, 0x01] + serverTlsCertHash (SHA-384) + nonce
```

### 3. Server authentication request (command 1 → response command 2)

The server replies with command 1 containing its own view of the TLS
certificate hash plus a server nonce. The agent:

1. Confirms the server's reported TLS hash matches its own `serverTlsCertHash`.
   A mismatch aborts the connection.
2. Signs `serverTlsCertHash + serverNonce + agentNonce` with the agent's private
   key using `SHA384withRSA`.
3. Replies with command 2: its agent certificate and the signature.

This proves to the server that the agent owns the private key for its identity
and that both sides are on the same TLS session.

### 4. Server certificate and signature (command 2)

The server sends its own certificate and a signature. The agent:

1. Computes a hash of the server certificate's public key and compares it to
   `serverCertHash` from the pairing link. This is what proves the agent is
   talking to the **correct** server, not just any server.
2. Verifies the server's signature over
   `serverTlsCertHash + agentNonce + serverNonce`.

If either check fails, the connection is dropped. On success the agent marks the
server side of the connection as verified and sends its core information
(command 3): agent type, platform, device group id, capabilities, and device
name.

### 5. Server confirms (command 4)

When the server accepts the agent's identity, it sends command 4. Once both the
server-verified bit and the server-confirmed bit are set, the channel becomes
fully authenticated (`state = 3`) and normal JSON messaging begins.

## How a Tunnel Is Requested

Remote sessions are never initiated by an unauthenticated peer. The server sends
a JSON `msg` of type `tunnel` **over the authenticated control channel**:

```json
{
  "action": "msg",
  "type": "tunnel",
  "value": "*/meshrelay.ashx?...",
  "usage": 5,
  "servertlshash": "97eaf674...",
  "userid": "user//admin",
  "username": "admin",
  "rights": 4294967295,
  "consent": 0
}
```

Key fields:

- `value` is the relay URL. It carries a one-time, server-generated
  authentication cookie in its query string. A leading `*/` is rewritten to
  `wss://<host>/meshrelay.ashx?...`.
- `servertlshash` is the SHA-384 hash the tunnel must see on the relay server's
  TLS certificate.
- `usage` selects the session type: `2` = remote desktop, `5` = files,
  `10` = file transfer.
- `userid`, `username`, `rights`, and `consent` describe the operator and their
  permissions for consent prompts and the privacy bar.

The agent constructs a `MeshTunnel` from this message and starts it. See the
`"tunnel"` handler in
[MeshAgent.kt](../app/src/main/java/com/meshcentral/agent/MeshAgent.kt) and
`Start()` in
[MeshTunnel.kt](../app/src/main/java/com/meshcentral/agent/MeshTunnel.kt).

## Tunnel Trust: Certificate Pinning

The tunnel opens a new WebSocket to the relay URL. Its `TrustManager` pins the
relay server's TLS certificate: it computes the SHA-384 hash of the presented
certificate and accepts the connection only if the hash matches either:

- the `servertlshash` supplied in the authenticated tunnel request, or
- the `serverTlsCertHash` recorded on the control channel.

Any other certificate throws a `CertificateException` and the tunnel is closed.
This ties the relay connection back to the server the agent already
authenticated, so the one-time relay cookie cannot be replayed against a
different TLS endpoint.

See `getUnsafeOkHttpClient()` / `checkServerTrusted` in
[MeshTunnel.kt](../app/src/main/java/com/meshcentral/agent/MeshTunnel.kt).

## Relay Handshake and Session State

After the relay socket connects, the tunnel advances through a small state
machine driven by `onMessage`:

1. **State 0 → 1:** the relay sends the text `c` or `cr` to signal the two
   endpoints are connected.
2. **State 1 → 2:** the relay sends the numeric `usage` (and optional
   `options` JSON). The agent validates the usage value and confirms it matches
   the `usage` from the original tunnel request via `isTunnelUsageAllowed(...)`.
   A mismatch closes the tunnel.
3. **State 2:** the session is live and binary session data flows (screen tiles,
   file listings, uploads, or a file stream).

## User Consent

For a remote desktop tunnel (`usage == 2`), if the automatic-consent preference
is off and no capture session is active, the agent asks the user to grant screen
sharing before streaming begins. With automatic consent enabled, or an existing
capture already running, the session starts immediately.

## Security Notes

- The custom TLS trust managers intentionally accept any certificate at the
  transport layer and skip hostname verification. Security is provided by the
  application-layer handshake and by pinning the server TLS certificate hash on
  both the control channel and every tunnel. Do not "simplify" these trust
  managers into standard CA validation without understanding this design, as it
  supports self-hosted servers with self-signed certificates.
- A tunnel has no independent password or key exchange. It trusts:
  1. that the tunnel request arrived on the mutually authenticated control
     channel,
  2. the one-time relay cookie embedded in the relay URL, and
  3. the pinned relay TLS certificate hash.
- The agent private key never leaves the device and is what proves the agent's
  identity to the server during the control-channel handshake.
