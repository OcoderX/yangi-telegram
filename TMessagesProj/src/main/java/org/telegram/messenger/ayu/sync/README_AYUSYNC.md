# AyuSync (retired Android client)

The legacy AyuSync server client is retired. It no longer starts with the app or on configuration
changes. Loading settings clears the old `syncEnabled` preference, including values saved by an
older installation.

`AyuSyncController` retains its public API for compatibility, but creates no observers, background
queue, device identifiers or network connections. `start()`, `checkState()` and `forceSync()` are
inert; status remains `DISABLED`. `registerDevice()` reports failure on the UI thread without making
a request. There are no active event uploads or polling, even if old code sets the legacy flag.

Local deleted/edited message history and the upload queue are independent and remain available.
The current OcoderX-sync credentials screen stores its settings in `OxSyncConfig`; those settings
are separate from the retired client and are unchanged.
