# PhoneDrop protocol v2

The phone app talks to the Mac app over HTTP/1.1 on the local network. Every
request is authenticated and every body is encrypted with a key that is
shared once, in person, through a QR code.

## Pairing

The Mac creates a random 32-byte key `K` on first launch and stores it in
`~/Library/Application Support/PhoneDrop/key`. Its QR code encodes:

```
phonedrop://pair?v=2&k=<base64url K>&h=<IPv4>&p=<port>&n=<url-encoded Mac name>
```

`kid` = the first 16 hex characters of `SHA-256("phonedrop-kid" || K)`. It
identifies the Mac without revealing `K`.

"Reset pairing" on the Mac replaces `K`, so every phone has to pair again.

## Discovery

The Mac advertises Bonjour service `_phonedrop._tcp` with the TXT record
`kid=<kid>` and `v=2`. The phone tries the last known `h:p` first. If that
fails, it browses for the service whose `kid` matches.

## Crypto

All encryption is AES-256-GCM with key `K`, a 12-byte random nonce and a
16-byte tag. A sealed box is written as `nonce(12) || ciphertext || tag(16)`,
so it is 28 bytes longer than the plaintext. Every box has an AAD string
(UTF-8) that binds it to its purpose:

| What                  | AAD                         |
|-----------------------|-----------------------------|
| Request auth header   | `pd2 auth`                  |
| JSON request body     | `pd2 req <path>`            |
| JSON response body    | `pd2 res <path>`            |
| Upload chunk body     | `pd2 chunk <id> <index>`    |
| Download frame        | `pd2 dl <fileId> <frame>`   |

`<path>` is the URL path without the query, for example `/api/init`.

### Request authentication

Every request carries `X-PD-Auth: base64url(seal("<METHOD> <path?query> <unix seconds>"))`.
The server rejects the request with 403 if:

- the box does not open,
- the method or target does not match the request, or
- the time differs from the Mac's clock by more than 300 s.

Error responses are plain JSON `{"error": "..."}` with a non-2xx status.
Successful JSON responses are sealed.

## Endpoints

| Method | Path                         | Request body            | Response body |
|--------|------------------------------|-------------------------|---------------|
| POST   | `/api/hello`                 | `{device}`              | `{name, v}`   |
| POST   | `/api/init`                  | `{name,size,mtime,chunk,dir?}` | `{id,chunk,done:[i]}` |
| POST   | `/api/folders`               | –                       | `{root, folders:[name]}` |
| PUT    | `/api/chunk?id=&i=`          | sealed chunk            | `{}`          |
| POST   | `/api/finish?id=`            | –                       | `{name}`      |
| POST   | `/api/cancel?id=`            | –                       | `{}`          |
| POST   | `/api/outbox`                | –                       | `{files:[{id,name,size,mtime}]}` |
| GET    | `/api/dl?f=<fileId>&from=<frame>` | –                  | frame stream  |

### Uploads (phone → Mac)

- `id` = the first 32 hex characters of `SHA-1("<name>|<size>|<mtime>|<chunk>")`.
  Choosing the same file again therefore resumes the same transfer.
- `chunk` is 1–64 MiB. The phone uses 8 MiB.
- Chunks are idempotent. The phone sends up to 4 in parallel and retries
  forever with backoff.
- `mtime` is in Unix milliseconds, or 0 if unknown. The Mac sets it as the
  file's modification date.
- `finish` returns 409 while chunks are missing.
- `dir` is an optional subfolder of the Mac's download folder, such as `Wedding 2026` or `Trips/Rome`.
  The Mac drops empty and `..` parts, cleans each name and keeps at most four levels, so a file can
  never land outside the download folder. `/api/folders` lists the existing top-level subfolders,
  newest first.

### Downloads (Mac → phone)

The plaintext is split into 1 MiB frames. Each frame is sent as
`u32 big-endian box length || sealed box`. `from` lets an interrupted download
resume at a frame boundary. When a stream reaches the end of the file, the Mac removes the
file from its outbox.

## Direct mode (no router)

When the phone can't reach the Mac over the network, it can connect directly. Bluetooth LE
carries only the handshake; the files still go over Wi-Fi.

1. The Mac advertises GATT service `C0DE0001-9D4B-4F2A-8E61-50484F4E4544` whenever the app runs.
2. The phone scans for it, connects, and reads **INFO** (`C0DE0002-…`): the Mac's `kid` as plain
   UTF-8. If it doesn't match the paired Mac, the phone disconnects and keeps looking.
3. The phone starts an Android *local-only hotspot*: random SSID and password, no internet.
4. The phone writes **JOIN** (`C0DE0003-…`): `seal({"s": ssid, "p": password, "t": unix seconds}, "pd2 ble join")`.
   The Mac rejects boxes that don't open or are more than 300 s old.
5. The Mac joins the hotspot with `networksetup -setairportnetwork`. It then publishes its address in
   **STATUS** (`C0DE0004-…`, read):
   `seal({"st": "joining" | "ready" | "error", "ip": "...", "p": port, "e": reason, "t": unix seconds}, "pd2 ble status")`.
   The phone polls STATUS until it reads `ready` or `error`.
   STATUS always carries `lan` and `lp` too: the Mac's address and port on the network it is on right
   now. Before starting its hotspot (step 3), the phone reads STATUS once and tries that address; if
   the Mac answers there, the phone uses the normal network and skips the rest.
6. The transfer runs over HTTP exactly as on a normal network.
7. When the queue is empty, the phone sends `POST /api/direct/end` and stops the hotspot. The Mac then
   forgets the hotspot network and macOS rejoins its usual Wi-Fi by itself.

If the Mac is already on a Wi-Fi network, it refuses to switch (`"e": "on-wifi"`) unless the user
turned on "Allow Direct Connection on Wi-Fi". That way a phone can't pull a working Mac off its network
without the user agreeing to it.

## Sending from the Mac

The phone normally pulls: it lists the outbox and downloads what the user taps. When the user presses
**Send** on the Mac, the Mac advertises `C0DE0005-…` *instead of* `C0DE0001-…` (only one 128-bit UUID fits
in an advertisement) until the outbox is empty, or for two minutes after the last activity. The phone
keeps a low-power background scan for `C0DE0005-…` registered with Android. When it fires, the app
lists the outbox of its paired Mac and downloads everything. A Mac the phone isn't paired with can
trigger the scan, but the phone only ever talks to its own Mac, so nothing is downloaded.

All characteristic and service UUIDs share the suffix `-9D4B-4F2A-8E61-50484F4E4544`.
