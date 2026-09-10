# AyuSync (Android client)

Upstream AyuGram implements AyuSync as a **WebSocket** service. Bringing a WebSocket client into this
fork would require a new Gradle dependency, which is not allowed here, so `AyuSyncController` speaks
the same event model over plain HTTP using `java.net.HttpURLConnection`. Swapping the transport later
only means replacing `pollEvents()` / `flushOutgoing()`; the event objects stay identical.

## Transport

* Base URL comes from `AyuConfig.syncServerURL`. The scheme stored in the setting is ignored and
  rebuilt from `AyuConfig.useSecureConnection` (`https://` when on, `http://` when off), so pasting
  `wss://sync.example.org/`, `https://sync.example.org` or `sync.example.org` all work.
* Every request carries:
  * `Authorization: Bearer <AyuConfig.syncServerToken>` (omitted when the token is empty)
  * `X-Device-Id: <Settings.Secure.ANDROID_ID>`
  * `User-Agent: AyuGram-Android`
  * `Content-Type: application/json; charset=utf-8` on POSTs
* Timeouts: 15 s connect, 35 s read. The read timeout is intentionally longer than the poll interval
  so a server that holds the request open (long poll) works without changes.

## Endpoints

### `GET /v1/events?since=<unix seconds>`

Polled every 10 s. `since` is the `ts` of the newest event already applied (0 on first run).

Response (either shape is accepted):

```json
{ "now": 1750000123, "events": [ { "type": "read", ... } ] }
```

```json
[ { "type": "read", ... } ]
```

### `POST /v1/events`

Sends the locally produced events accumulated since the previous tick. If the request fails the batch
is put back at the head of the queue and retried on the next tick (queue capped at 500 events,
oldest dropped first).

```json
{ "deviceId": "<ANDROID_ID>", "events": [ { "type": "deleted", ... } ] }
```

### `POST /v1/register`

One-shot device registration triggered from the settings screen. Response is scanned for `token`
(falling back to `accessToken`); when present it is written to `AyuConfig.syncServerToken`.

```json
{ "deviceId": "<ANDROID_ID>", "app": "AyuGram-Android" }
```

## Events

Every event carries `type` and `ts` (unix seconds). `userId` is the Telegram id of the account that
owns the event, which is what lets a multi-account client route an incoming event to the right slot.

| type | fields |
| --- | --- |
| `read` | `userId`, `dialogId`, `topicId`, `maxId` |
| `deleted` | `userId`, `dialogId`, `ids[]` |
| `edited` | `userId`, `dialogId`, `id`, `oldText` |
| `ghost` | `enabled` |

### Outgoing (this client -> server)

| event | source |
| --- | --- |
| `read` | `NotificationCenter.messagesRead` (inbox map), per account |
| `deleted` | `NotificationCenter.ayuMessageHistoryUpdated` with `type == 0` |
| `edited` | `NotificationCenter.ayuMessageHistoryUpdated` with `type == 1`; `oldText` is read back from `AyuMessagesController.getRevisions(...)`, empty when no revision is stored |
| `ghost` | `NotificationCenter.ayuGhostModeChanged` |

### Incoming (server -> this client)

* `read` is applied **locally only**: `MessagesStorage.updateDialogsWithReadMessages(inbox, ...)`
  followed by a UI-side `messagesRead` + `dialogsNeedReload` post. Nothing is sent to Telegram, so a
  read synced from another device never breaks ghost mode. The applied `(account, dialogId, maxId)`
  triple is remembered briefly so the `messagesRead` it raises is not echoed straight back out.
* `ghost` calls `AyuConfig.setGhostMode(enabled)` on the UI thread and posts `ayuConfigChanged`.
* `deleted` / `edited` are **not** written back. The local history database is authoritative; these
  events exist so other AyuSync consumers can see them.

## Robustness

* One background `DispatchQueue` ("AyuSyncQueue") does all networking; nothing blocks the UI thread.
* Failures increase a counter; the next tick is delayed by `min(300 s, 5 s * 2^(failures-1))`.
  A successful tick resets it back to the 10 s cadence.
* Malformed JSON is caught per-event and per-response and only logged via `FileLog`; the loop keeps
  running.
* `stop()` bumps a generation counter, so runnables queued by a previous run exit immediately.

## Status

`AyuSyncController.getStatus()` returns `DISABLED` / `CONNECTING` / `CONNECTED` / `ERROR`, with
`getLastError()` holding the last failure message. `addListener` / `removeListener` take a plain
`AyuSyncListener` (no `NotificationCenter` involvement) which the settings screen uses to keep the
status row live.
