// The parts of the C library used by Tessera's event loop and Wayland sockets.
// Only struct layouts and constants are generated from here; calls to the
// functions are written by hand in dev.tessera.ffi.Libc so `errno` can be read.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
