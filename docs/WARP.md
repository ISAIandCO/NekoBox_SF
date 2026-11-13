# Cloudflare WARP in NekoBox_SF

## Usage

Open **Routing → ⋮ → Cloudflare WARP**, generate a WireGuard or AmneziaWG configuration, select it, and enable **Use WARP as exit**. Accept the Cloudflare terms before registering the first device. The active VPN is restarted when the exit setting changes.

The resulting path is **device → selected VPN → WARP → destination**. Select the main VPN normally, including subscription profiles, chains, Fastest, Waterfall and selector groups. The main VPN must carry UDP. There is no direct or main-VPN-only fallback for traffic assigned to a failed WARP exit. Disable the WARP switch to restore ordinary routing.

Like Throne, the exit wraps the current main VPN. Direct/LAN routes, Android apps excluded from the VPN, and rules explicitly selecting a different profile retain their routing. Direct-route fallback through the current VPN uses WARP when that fallback is selected. A fallback explicitly selecting another profile retains that profile. Imported WireGuard AllowedIPs still apply if that option is enabled.

Configurations can be renamed, refreshed using their existing device ID/token/key, removed locally, or revoked at Cloudflare and removed. A failed refresh/revocation leaves the saved configuration intact. **Add copy to Profiles** creates an independent, editable WG/AWG profile for standalone WARP or use in an existing chain. Refreshing the saved exit does not rewrite exported copies; revoking the device also invalidates copies using its key. Do not use the same device simultaneously as the main VPN and its WARP exit.

## Implementation and differences from Throne

- Reference inspected: [Throne commit 98fb0bc0ea57396b067b4ab46f6b4b821068df40](https://github.com/throneproj/Throne/tree/98fb0bc0ea57396b067b4ab46f6b4b821068df40), especially `core/internal/warp`, `core/internal/amnezia/signature.go`, and `src/configs/generate.cpp`. The offline QUIC signature generator is adapted from that GPL-3.0 implementation.
- Both modes use the existing pinned sing-box compatibility layer and official AmneziaWG v3 implementation. No replacement VPN core or new runtime dependency is needed. AmneziaWG uses Jc=4, Jmin=40, Jmax=70 and an offline-generated Chrome QUIC first flight. Handshake headers remain 1/2/3/4 and size additions remain zero because Cloudflare expects the WireGuard wire format. This is AWG camouflage compatible with Cloudflare, not arbitrary obfuscation requiring an AWG server.
- The compatibility overlay also fixes a reproduced pause deadlock: bind send/receive retry waits now stop when the bind closes. Device shutdown no longer waits for a sender that is itself waiting for device wake. The normal one-second error retry delay remains bounded and cancellable.
- Registration generates a fresh X25519 key locally and registers only its public key. Device token, private key, peer key, client ID/reserved bytes and interface addresses are retained in the existing private settings database and included in in-app backups. Android cloud backup excludes the database, as before.
- Registration, refresh and revocation run in the VPN service process. With a connected VPN their HTTPS requests use the core's current default outbound, with no direct fallback. Without a running VPN, registration uses direct HTTPS. Timeouts, HTTP/rate-limit errors and malformed responses are reported without displaying response bodies or registration secrets. Registration POST is never automatically retried.
- WARP uses a literal IPv4 endpoint supplied by Cloudflare to avoid circular endpoint DNS. Existing remote DNS follows the WARP exit; DNS hijacking, direct-route DNS and bootstrap resolution of VPN/DNS hostnames retain their existing roles. The wrapper does not introduce a local-DNS fallback for proxied destination queries.
- There is one WARP tunnel per active configuration, in front of the main VPN/selector. Creating several tunnels with the same key through independent upstreams would cause WireGuard peer roaming at Cloudflare. Selector profile tags remain unchanged, preserving main-server switching.
- Full custom sing-box configurations, root overrides of routing/outbounds/endpoints/inbounds/DNS and custom rule outbound/action overrides are rejected while the automatic WARP exit is enabled, rather than silently skipping it. Disable the exit or build an explicit custom chain for those cases.
- MASQUE is excluded by the requested scope clarification. Throne's configurable API hosts/TLS fragmentation and MASQUE-specific settings are not added. This implementation retains multiple named WG/AWG registrations instead of one identity per tunnel family.

## Verification

`libcore/warp` tests exercise registration key matching, identity refresh/revocation, malformed API responses, rate limiting, secret-safe errors, no direct fallback after a failed VPN dial, and offline AWG camouflage. The core lifecycle test exchanges TCP and UDP with a local WireGuard peer in WG and Cloudflare-compatible AWG modes, directly and through a local encrypted Shadowsocks relay, including device pause/wake and selector switching. A deterministic bind regression test failed before the pause fix and passes after it. Android unit tests check the generated exit options and loop rejection.

An earlier SOCKS relay stress test also exposed an existing read/write race in the pinned upstream sing SOCKS server's `serverPacketConn.remoteAddr`. That server-side issue is outside this WARP-client change and is not fixed here; the repeatable encrypted-chain fixture uses Shadowsocks. Basic SOCKS-chain traffic was exercised, but this work does not certify the application's separate SOCKS-server mode as race-free.

These local peers do not certify Cloudflare service availability. Real Cloudflare registration/handshake, external WARP IP, Android VPN capture/DNS leak behavior and real provider UDP support still require a device and an unrestricted network. No successful external-IP test is claimed by the automated tests.

## Builds and releases

The existing workflow triggers and signing configuration are retained. Manual builds have a **publish** checkbox, off by default: unchecked builds upload APK artifacts without creating a GitHub Release. Publication is an explicit separate choice when launching that workflow.

- A checked-out Git tag controls the version: `v2.03` becomes `versionName=2.03`. A conflicting manual version input is rejected.
- For a manual branch build, the `tag` input sets the intended version/release tag. The selected workflow ref controls the source commit. With an empty input, the workflow generates `v<VERSION_NAME>-<run_number>` from `nb4a.properties`.
- CI uses `versionCode = (1,000,000 + GITHUB_RUN_NUMBER) × 5`, independent of stale properties in older refs. New runs of the same workflow increase it; retrying a run retains its code. APK filenames and release titles use the resulting version.
- Release notes use only `docs/releases/<version>.md` for that exact version, otherwise the current commit message, otherwise a placeholder. Existing releases are rejected before publishing; no historical `2.00` note is implicitly reused for another version.
- `nb4a.properties` is still the single source for ordinary local build defaults. Build-only CI artifacts carry the normal configured signature; signing keys and package IDs are unchanged by the WARP feature.
