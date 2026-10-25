#!/usr/bin/env python3
"""Prints the title of the focused window, and again each time the focus moves - e.g. for a
bar. Talks to the compositor's IPC socket directly (see caulis.proto).

    protoc --proto_path=../../caulis-ipc/src/main/proto --python_out=. caulis/ipc/v1/caulis.proto
    python3 focused_title.py

Needs the protobuf package (pip install protobuf, or nix shell nixpkgs#python3Packages.protobuf).
"""

import glob
import os
import socket
import sys

from google.protobuf.internal.decoder import _DecodeVarint32
from google.protobuf.internal.encoder import _VarintBytes

from caulis.ipc.v1 import caulis_pb2 as caulis


def find_socket():
    """$CAULIS_SOCKET, else the compositor socket in $XDG_RUNTIME_DIR."""
    if os.environ.get("CAULIS_SOCKET"):
        return os.environ["CAULIS_SOCKET"]
    found = glob.glob(os.path.join(os.environ["XDG_RUNTIME_DIR"], "*-wayland-*.sock"))
    if len(found) != 1:
        sys.exit("Set CAULIS_SOCKET (found: %s)" % (found or "none"))
    return found[0]


class Connection:
    def __init__(self, path):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(path)
        self.data = b""
        self.next_id = 1

    def send(self, request):
        """Sends a Request (size first, as a varint) and returns the Reply to it."""
        request.id = self.next_id
        self.next_id += 1
        body = request.SerializeToString()
        self.sock.sendall(_VarintBytes(len(body)) + body)
        while True:
            message = self.receive()
            if message.HasField("reply") and message.reply.id == request.id:
                if message.reply.HasField("error"):
                    raise RuntimeError(message.reply.error)
                return message.reply

    def receive(self):
        """The next Message from the compositor."""
        while True:
            if self.data:
                size, start = _DecodeVarint32(self.data, 0)
                if len(self.data) >= start + size:
                    message = caulis.Message()
                    message.ParseFromString(self.data[start:start + size])
                    self.data = self.data[start + size:]
                    return message
            chunk = self.sock.recv(65536)
            if not chunk:
                sys.exit(0)  # the compositor went away
            self.data += chunk


def main():
    ipc = Connection(find_socket())
    titles = {}

    state = ipc.send(caulis.Request(get_state=caulis.GetState())).state
    for window in state.windows:
        titles[window.id] = window.title
    print(titles.get(state.focused_window, ""), flush=True)

    ipc.send(caulis.Request(subscribe=caulis.Subscribe(kinds=[caulis.EVENT_KIND_WINDOWS, caulis.EVENT_KIND_FOCUS])))
    focused = state.focused_window
    while True:
        event = ipc.receive().event
        kind = event.WhichOneof("what")
        if kind in ("window_opened", "window_changed"):
            window = getattr(event, kind)
            titles[window.id] = window.title
            if window.id != focused:
                continue
        elif kind == "focus_changed":
            focused = event.focus_changed
        else:
            continue
        print(titles.get(focused, ""), flush=True)


if __name__ == "__main__":
    main()
