# Talking to a running compositor

Every compositor made with Caulis has an IPC socket (`$CAULIS_SOCKET`, set for the programs
it starts). What goes over it is described in
[`caulis.proto`](../../caulis-ipc/src/main/proto/caulis/ipc/v1/caulis.proto), so any language
with Protocol Buffers can talk to it.

**From a shell** — the compositor's own program does it, with JSON:

```sh
my-compositor --do exec firefox
my-compositor --do close --window 7
my-compositor --state | jq '.windows[] | .title'
my-compositor --events focus workspaces | while read -r event; do echo "$event"; done
```

**From Python** — [`focused_title.py`](focused_title.py) prints the focused window's title
whenever it changes (a bar's title widget in 100 lines):

```sh
protoc --proto_path=../../caulis-ipc/src/main/proto --python_out=. caulis/ipc/v1/caulis.proto
python3 focused_title.py
```

**From Kotlin or Java** — `dev.caulis:caulis-ipc` has a client:

```kotlin
CaulisIpc.connect().use { ipc ->
    ipc.run("workspace 2")
    ipc.subscribe(EventKind.EVENT_KIND_FOCUS)
    while (true) println(ipc.nextEvent() ?: break)
}
```
