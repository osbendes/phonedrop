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
| POST   | `/api/init`                  | `{name,size,mtime,chunk}` | `{id,chunk,done:[i]}` |
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

### Downloads (Mac → phone)

The plaintext is split into 1 MiB frames. Each frame is sent as
`u32 big-endian box length || sealed box`. `from` lets an interrupted download
resume at a frame boundary.
