# VDO.Ninja publisher signalling (for Mantra Manual Camera)

**Written:** 14.11.2026. **Purpose:** the Android app publishes its own frames into VDO.Ninja exactly as the browser publisher `https://vdo.ninja/?push=<id>&password=<pw>` does, so the unchanged viewer `?view=<id>&password=<pw>` shows it. No WHIP (whip.vdo.ninja is a separate `?whip=` path with no password).

**Sources read (all on 14.11.2026):** `steveseguin/raspberry_ninja` (publish.py, signaling_utils.py, turn_servers.py, shallow clone), `steveseguin/vdo.ninja` develop (webrtc.js, main.js, lib.js, LICENCE.md). The sdk.vdo.ninja docs were NOT read; everything below comes from the source code. Line numbers are from those files as of that date.

## 1. Websocket and handshake

| Item | Value |
|---|---|
| URL | `wss://wss.vdo.ninja:443` (webrtc.js `session.wss`; raspberry_ninja default). Backup host in raspberry_ninja: `apibackup.vdo.ninja`. |
| Subprotocol / headers | None. Plain text JSON frames. Browser sends no auth on open. |
| Handshake | There is none. Open the socket, then send the first JSON message (`seed`). The hosted server assigns the socket a UUID itself. |
| `from` field | Needed only for custom servers (raspberry_ninja generates a random `puuid` and adds `"from"` to every message only when the server is not wss.vdo.ninja / apibackup.vdo.ninja). On the official server do not send `from`. |
| Size limit | Browser refuses to send frames over 10000 chars when a password is set (35000 for `description`/`candidates`). Keep the SDP small (one video, one audio, one data channel). |
| Ping | The server may send `{"request":"ping","id":..}`; answer `{"request":"pong","id":<same id>}` on the same socket (webrtc.js `respondToServerPing`). raspberry_ninja does not answer it and still works; answer it anyway. |

Messages the server relays to us carry `"UUID":"<viewer uuid>"` (the sender). Every message we send to one viewer must carry `"UUID":"<that viewer uuid>"` so the server routes it.

## 2. Message sequence (publisher)

```
Publisher                 wss.vdo.ninja                 Viewer (?view=)
 | open wss                    |                              |
 |-- {"request":"seed",        |                              |
 |    "streamID":"<id><h6>"} ->|                              |
 |                             |<- {"request":"play",         |
 |                             |    "streamID":"<id><h6>"} ---|  viewer joined / asks for stream
 |<- {"request":"play" or      |                              |
 |    "offerSDP","UUID":V1,    |                              |
 |    "streamID":"<id><h6>"}   |                              |
 | create PeerConnection #1 for V1; add tracks; create data channel "sendChannel"; createOffer
 |-- {"UUID":V1,"session":S1,"streamID":"<id><h6>",           |
 |    "description":"<hex ct>","vector":"<hex iv>"} ---------->|--> viewer
 |                             |<-- {"UUID":V1,"session":S1,"description":ct,"vector":iv}  (answer)
 |-- {"UUID":V1,"session":S1,"type":"local",                  |
 |    "candidates":"<hex ct>","vector":"<hex iv>"} ----------->|   (trickle; bundle)
 |<-- {"UUID":V1,"session":S1,"candidates":ct,"vector":iv}     |   (viewer ICE)
 | ICE connected -> media flows; data channel opens; ping/pong |
 |<- {"request":"play",...,"UUID":V2} second viewer: repeat with a NEW PeerConnection and session S2
```

Details, with where they come from:

1. **Seed.** `{"request":"seed","streamID":"<id><hash6>"}`. The browser sends the bare id and `sendMsg` appends the hash (webrtc.js 6840-6860: `msg.streamID = msg.streamID.substring(0,64) + session.hash.substring(0,6)`); raspberry_ninja appends it itself (`stream_id + hashcode`). On the wire the id is therefore always `<id><6 hex>`. There is no acknowledgement. Re-send `seed` after every websocket reconnect (webrtc.js `seedStream()` in onopen when `reconnect`).
2. **Viewer request.** The viewer sends `{"request":"play","streamID":...}` (`watchStream`). The hosted server delivers it to the seeder; raspberry_ninja accepts both `request:"play"` with a matching `streamID` and `request:"offerSDP"` (publish.py 12101, and `'offerSDP' in msg['request']`), the browser accepts `offerSDP` (webrtc.js 7915). Treat both as "viewer UUID wants an offer". The viewer UUID is `msg.UUID`.
3. **One PeerConnection per viewer UUID.** Keep `map<UUID, Peer>`. If a request arrives for a UUID that has a connected peer, ignore it; if it is stuck in connecting or failed, close it and build a new one (webrtc.js `offerSDP`). Each peer gets its own random `session` string (browser: `loadoutID + 5 random chars`; any non-empty random string is fine). Every later message for that peer carries it, and the viewer drops answers whose `session` differs (webrtc.js 16310). raspberry_ninja sends `session:null` for the first offer and works, but send a real string.
4. **Offer.** After `setLocalDescription(offer)`: `{"description": <enc>, "vector": <iv>, "UUID": V, "session": S, "streamID": "<id><h6>"}`. The browser omits `streamID` here; raspberry_ninja includes it; both work. Tracks (video sendonly, audio sendonly) and the data channel must be added BEFORE createOffer (raspberry_ninja creates the data channel after connection-state 2, so it is not in the first offer; the browser creates `createDataChannel("sendChannel")` right after building the PC, webrtc.js 12445). Do like the browser.
5. **Answer.** `msg.description` (decrypt it, see 3) is `{"type":"answer","sdp":"..."}`; check `msg.session == S`, then `setRemoteDescription`.
6. **Trickle ICE.** Each local candidate is queued; the browser flushes bundles with a timer and sends `{"UUID":V,"session":S,"type":"local","candidates":<enc of JSON array>,"vector":iv}`. The array elements are `{"candidate":"candidate:...","sdpMLineIndex":0,"sdpMid":"0"}`. raspberry_ninja sends one candidate per message in a one-element array, skips TCP candidates, and works the same. Empty candidate strings are ignored by the viewer. Incoming viewer candidates arrive as `candidates` (array, encrypted) or `candidate` (single); decrypt, then `addIceCandidate` each. Queue them until the remote description is set.
7. **Bye.** Viewer leaves: `{"bye":true}` over the data channel, or the PC goes `failed/closed`; close that peer only. When we stop: send `{"bye":true}` over each data channel, close every PC, close the socket.

## 3. What the password changes

All values are in webrtc.js / main.js / lib.js and match raspberry_ninja (`generateHash`, `encrypt_message`, `decrypt_message`, `normalize_vdo_password`).

**Password normalisation** (main.js 3370-3402, lib.js `sanitizePassword`): URL-decode once (the URLSearchParams value), `trim()`, then `encodeURIComponent`. For 12 hex chars this is the identity. Implement it anyway: `pw = percentEncode(pw.trim())` with the unreserved set `A-Za-z0-9-_.!~*'()` left alone. The literal strings `false`, `0`, `off` mean "no password".

**Salt** (webrtc.js 1795-1815): `"vdo.ninja"` when the viewer page runs on vdo.ninja (also steveseguin.github.io, IPs, localhost); `"vdo.ninja"`/`"rtc.ninja"`/`"versus.cam"`/`"socialstream.ninja"` (last two host labels) for official sub-domains; otherwise the viewer page's own full hostname. The viewer URL can override it with `&salt=`. raspberry_ninja derives it from the websocket hostname (`wss.vdo.ninja` -> `vdo.ninja`) unless `--salt` is given. **It must equal the viewer page's salt.**

**Stream ID hash:** `hash6 = hex(SHA-256(utf8(pw + salt))[0..3])`, i.e. the first 3 bytes, 6 lowercase hex chars (`generateHash(s, 6)` keeps `length/2` bytes). Wire id = `id + hash6`. (Rooms use `generateHash(room + pw + salt, 16)`, 8 bytes; not needed for `?push`/`?view` without `&room`.)

**AES encryption of `description` and `candidates`** (webrtc.js 3209 `encryptMessage`):

- key = `SHA-256(utf8(pw + salt))`, the 32 raw bytes (the same digest whose first 3 bytes give hash6). No PBKDF2, no extra salt.
- cipher = AES-256-CBC, PKCS#7 padding (WebCrypto default).
- IV = 16 random bytes, fresh for every message.
- plaintext = `JSON.stringify(x)` as UTF-8, where x is the description object `{"type":..,"sdp":..}` or the candidates array. Not the bare SDP string.
- output = ciphertext as lowercase hex placed in the same field name (`description` / `candidates` / `candidate`), IV as lowercase hex in a sibling field `"vector"`.
- Decrypt is the reverse; if `vector` is present the field is encrypted. Messages without `vector` are rejected by the viewer when a password is set on the viewer side. Non-encrypted fields (`UUID`, `session`, `type`, `streamID`, `request`) stay in clear. Data channel messages are not encrypted (only `candidate(s)` and `description` inside them, same scheme).

Pseudocode (Kotlin-portable):

```
fun normalizePw(raw): String = percentEncode(raw.trim(), unreserved = "A-Za-z0-9-_.!~*'()")
fun sha256(bytes): ByteArray = MessageDigest("SHA-256")
fun hash6(pw, salt) = hex(sha256(utf8(pw + salt)).take(3))          // 6 hex chars
fun wireId(id, pw, salt) = id + hash6(pw, salt)
fun encrypt(obj, pw, salt):
    key = sha256(utf8(pw + salt))                                      // 32 bytes
    iv  = SecureRandom(16)
    ct  = AES/CBC/PKCS5Padding(key, iv).encrypt(utf8(jsonCompact(obj)))
    return (hex(ct), hex(iv))                                          // -> field, "vector"
fun decrypt(ctHex, ivHex, pw, salt):
    return jsonParse(utf8(AES/CBC/PKCS5Padding(sha256(utf8(pw+salt)), unhex(ivHex)).decrypt(unhex(ctHex))))
```

(Java's `PKCS5Padding` is PKCS#7 for 16-byte blocks. Lowercase hex both ways; the viewer parses either case.)

### Worked example (made-up values; computed with the Python reference, tested round trip)

Input: id `sb0123456789ab`, pw `a1b2c3d4e5f6`, salt `vdo.ninja`, IV fixed to `000102...0f` for the example only (real code: random).

| Step | Result |
|---|---|
| normalised pw | `a1b2c3d4e5f6` |
| `SHA-256("a1b2c3d4e5f6vdo.ninja")` (also the AES key) | `5802a2d224aede16c297532a4d2cc20b5aed37a1b4a9b8b3b3a618df0f617153` |
| hash6 (first 3 bytes) | `5802a2` |
| wire streamID | `sb0123456789ab5802a2` |
| seed message | `{"request":"seed","streamID":"sb0123456789ab5802a2"}` |
| plaintext (description) | `{"type":"offer","sdp":"v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n"}` |
| `vector` | `000102030405060708090a0b0c0d0e0f` |
| `description` (hex ciphertext) | `120a99858d745338e5caae40110bc82a753d2ff10c4f8ac09e47c35ddd09325c8efece93b9cbf357db1e42a5476c16f098652f0add5b73c4cc9df124e16c4998642b0bc4ed7b90e8d5793930e56b84db` |
| plaintext (candidates) | `[{"candidate":"candidate:1 1 udp 2113937151 192.168.1.5 54321 typ host","sdpMid":"0","sdpMLineIndex":0}]` |
| `candidates` (same IV) | `13c33f5011a67f11131a43b151d3b8e400d2c313c306aca52012d2519f2c9660adf2050dab1c85228a02001b06f8245104d9c33d6be8bcbded5865585dba42921fcc0f9545d786c75d7972cf687be3e822f18a1059c3b5867e41a848f74b9013c6d3c496b3fe7e375aa6c81d4022c50e` |

Use this as a unit test in the app: encrypt with the fixed IV, compare hex; decrypt must give the plaintext back. (Reference script: scratchpad `ref.py`, not committed.)

## 4. STUN / TURN

- STUN (browser default, webrtc.js 1247): `stun:stun.l.google.com:19302`, `stun:stun.cloudflare.com:3478`. raspberry_ninja: `stun://stun.l.google.com:19302` (README also shows cloudflare).
- TURN is discovered by `GET https://turnservers.vdo.ninja/` -> `{"version":1,"servers":[{"username":..,"credential":..,"urls":["turn:turn-de3.vdo.ninja:3478"],"udp":true,"distance":53}, ...]}`. Credentials are served per request (some are the public strings `vdoninja` / `IchBinSteveDerNinja`); do not hard-code them in the app, fetch at each GO LIVE (browser caches one hour). raspberry_ninja uses up to two UDP and one TCP/TLS entry, 2 s timeout, and falls back to `turn:turn-cae1.vdo.ninja:3478` + `turns:turn-cae1.vdo.ninja:443` (user `steve`, pass `setupYourOwnPlease`) if discovery fails. The browser's last-resort list also has `turn-use2.vdo.ninja` (3478 / turns 443, `vdoninja` / `pleaseUseYourOwn`).
- Use `bundlePolicy: max-bundle`, normal ICE policy `all`. On Android use `PeerConnection.IceServer` for the STUN and each TURN url.

## 5. Media and data channel

| Item | Expectation |
|---|---|
| Video | H.264 or VP8 both work; the viewer answers with what the offer lists first and the browser viewer defaults to preferring VP8 only for guest/low-bitrate cases (webrtc.js 16340+). For Android hardware encoding offer H.264 constrained baseline (`profile-level-id=42e01f`, `packetization-mode=1`) first, VP8 second. raspberry_ninja default is 1920x1080 at 30 fps, 2500 kbps (`--bitrate 2500`). |
| Audio | Opus 48000/2, `minptime=10;useinbandfec=1`. raspberry_ninja pipelines use Opus. |
| Direction | Both transceivers `sendonly`. We never receive. |
| Data channel | The browser always creates `sendChannel` (default options: ordered, reliable) on the publisher PC and raspberry_ninja creates it too. The viewer uses it for `{"info":{...}}`, ping/pong, `bye`, bitrate/requests, and later candidate exchange. I found no code path that tears the stream down only because it is absent, but it is NOT proven that the viewer behaves identically without it: create it, answer `{"ping":x}` with `{"pong":x}`, and send an `info` message on open (`{"info":{"label":"...", "muted":false}}`, optional). The viewer may send `{"bitrate":n}`, `{"requestResolution"..}` etc.; ignore unknown keys. |
| SDP edits | None required. raspberry_ninja applies bitrate `b=AS` lines; the viewer also clamps itself. |

## 6. Keepalives and reconnect

- **WebSocket:** no client keepalive is required by the hosted server in raspberry_ninja (`ping_interval=None`). Browser pings with `{"request":"ping"}` 3 s after a data channel closes, and answers server pings (section 1). On close the browser waits 5 s (`setTimeout(..., 5000)`; raspberry_ninja also 5 s) and reconnects, then re-sends `seed`. Established PeerConnections survive a signalling drop; viewers that arrive later need a live socket, so reconnect with a back-off and re-seed. Close code 503 = too many connections from one address.
- **Per peer:** the publisher sends `{"ping":"<time>"}` on the data channel every 3 s; the viewer answers `{"pong":<same>}`. raspberry_ninja counts unanswered pings and tears the peer down after 10 (about 30 s, "NO HEARTBEAT"); it also tears a peer down if it is not `connected` after its connect timeout. Do the same, then wait for the viewer's next `play`/`offerSDP`, which arrives when the viewer reloads or reconnects. Viewer can send `{"iceRestartRequest":true}` -> do an ICE restart (new offer, same session).
- **Same UUID asks again while connected:** ignore. While stuck: close and rebuild.

## 7. Licence and terms

- **raspberry_ninja** (`steveseguin/raspberry_ninja`, read 14.11.2026): the repository has **no LICENSE file** and GitHub reports no licence (`gh api .../license` -> 404); no licence text in README, publish.py or any other file. Without a licence all rights are reserved by default, so do **not copy its code** into the app. Treat it only as a reading reference for the protocol (facts about the wire format are not copyrightable, but write the Kotlin independently). The same applies to the strictly-defined algorithm above.
- **vdo.ninja** (`LICENCE.md`, `LICENSE`, file headers `SPDX-License-Identifier: AGPL-3.0-only`, Copyright 2020-2026 Steve Seguin): AGPL-3.0 only. Do not copy webrtc.js / main.js / lib.js code into the app either; implementing the protocol independently is fine. Note the AGPL network clause would apply to any copied code.
- **Hosted service terms, quoted from LICENCE.md:** "The software licenses also do not grant a contractual right to access or use infrastructure or services operated by Steve Seguin, including signaling, STUN/TURN, relay, API, or other hosted endpoints. Hosted-service access is separate from the software license, is governed by the applicable Terms of Service and operational policies, and may be limited, suspended, or withdrawn by the service operator." and "No trademark rights are granted ... and do not authorize anyone to claim affiliation, sponsorship, endorsement, or official status."
  So connecting a non-browser client to `wss.vdo.ninja` is not explicitly forbidden or allowed in the sources I read; it is governed by the separate (unread) Terms of Service, and access may be withdrawn. raspberry_ninja is the author's own non-browser client and its README documents using the public `wss.vdo.ninja` by default, which is a strong signal of tolerance, not a licence. Do not call the app "VDO.Ninja" or imply endorsement; send a descriptive client string if the server ever asks for one; fetch TURN from `turnservers.vdo.ninja` once per GO LIVE (do not poll). A self-hosted handshake server (the same JSON protocol, with `from` in each message) is the fallback if access is ever refused.

## 8. Open points (risk)

1. **Salt must match the viewer page.** If the Santoshi site embeds `https://vdo.ninja/?view=...` in an iframe, salt = `vdo.ninja`. If it serves its own copy of the viewer from another host, the salt is that hostname unless the link carries `&salt=`. Check the real viewer URL before the first test.
2. **Data channel optional?** Likely not needed for a pure viewer, not proven; create it.
3. **Exact `play` vs `offerSDP` naming from the hosted server** is taken from raspberry_ninja (accepts both) and the browser code (custom server converts play to offerSDP); handle both.
4. **ToS** of the hosted service not read (section 7).
5. **SDP details** (H.264 profile, bitrate caps) are untested against the viewer; first test with a static pattern before the camera.

## 9. What the first implementation found (v200–v205, 14–15.11.2026)

- **Confirmed against the hosted server and the real viewer page:** seed with `<id><hash6>`, no handshake, no `from`;
  the viewer's request reaches the publisher with its `UUID`; an offer encrypted as in section 3 (salt `vdo.ninja`,
  `description` hex + `vector`) is accepted; the viewer's encrypted answer decrypts to `{"type":"answer","sdp":...}`;
  ICE reaches CONNECTED with candidates sent one per message as `candidates` arrays. Nothing in sections 1 to 3 was
  wrong.
- **The site's /api/live answer:** `id` is the stream id; `push` is the browser publisher's whole URL
  (`https://vdo.ninja/?push=<id>&password=<pw>&...`), not the id. The app takes `id`, else the `push=` parameter.
- **Opus stereo** needs `stereo=1;sprop-stereo=1` in the answer as well as the offer (a sender encodes two channels only
  when the remote description allows it); the app adds it to both. Not yet heard at a viewer.
- Still open: whether the viewer prefers H.264 or VP8 from our offer (H.264 is listed first), the data channel's
  `info` message, the 3-viewer case.
