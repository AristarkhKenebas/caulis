# Module caulis-ipc

The IPC socket of a running compositor: its messages, described in
`src/main/proto/caulis/ipc/v1/caulis.proto` (programs in any language make their code from
it), and [dev.caulis.ipc.CaulisIpc], a client for programs on the JVM. The compositor's side
is part of caulis-wm.

# Package dev.caulis.ipc

[dev.caulis.ipc.CaulisIpc]: connect, run actions, ask what is on screen, hear what changes.

# Package dev.caulis.ipc.v1

The messages of caulis.proto, as Java classes (made by protoc; see the .proto for what each
field means).
