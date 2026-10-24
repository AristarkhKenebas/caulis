// The only module that talks to C libraries directly (through the FFM API).
// Everything above it uses safe Kotlin classes from here.

plugins {
    id("tessera.library")
}

val libs = versionCatalogs.named("libs")
fun version(name: String) = libs.findVersion(name).get().requiredVersion

// Generated bindings are kept in the repository, so a normal build needs neither
// jextract nor C headers. Regenerate with: ./gradlew :tessera-native:jextract
val generatedDir = layout.projectDirectory.dir("src/generated/java")

sourceSets.main {
    java.srcDir(generatedDir)
}

val jextractLibc = tasks.register<JextractTask>("jextractLibc") {
    group = "jextract"
    description = "Generates struct layouts and constants of the C library."
    header = layout.projectDirectory.file("src/main/c/libc.h")
    targetPackage = "dev.tessera.ffi.c.libc"
    headerClassName = "LibC"
    jextractUrl = version("jextract-url")
    jextractSha256 = version("jextract-sha256")
    toolsDir = gradle.gradleUserHomeDir.resolve("caches/tessera/tools")
    outputDir = generatedDir
    includes = listOf(
        // epoll: waiting for many file descriptors at once
        "struct epoll_event", "union epoll_data",
        "constant EPOLLIN", "constant EPOLLOUT", "constant EPOLLERR", "constant EPOLLHUP",
        "constant EPOLLRDHUP", "constant EPOLL_CTL_ADD", "constant EPOLL_CTL_MOD",
        "constant EPOLL_CTL_DEL", "constant EPOLL_CLOEXEC",
        // unix sockets and passing file descriptors between processes
        "struct sockaddr_un", "struct msghdr", "struct cmsghdr", "struct iovec",
        "constant AF_UNIX", "constant SOCK_STREAM", "constant SOCK_CLOEXEC", "constant SOCK_NONBLOCK",
        "constant SOL_SOCKET", "constant SCM_RIGHTS", "constant SO_PEERCRED",
        "constant MSG_CMSG_CLOEXEC", "constant MSG_DONTWAIT", "constant MSG_NOSIGNAL", "constant MSG_CTRUNC",
        "struct ucred",
        // shared memory: memfd and mmap
        "constant MFD_CLOEXEC", "constant MFD_ALLOW_SEALING",
        "constant PROT_READ", "constant PROT_WRITE", "constant MAP_SHARED", "constant MAP_PRIVATE",
        "constant F_ADD_SEALS", "constant F_SEAL_SHRINK", "constant F_SEAL_GROW", "constant F_SEAL_SEAL",
        "constant F_GETFL", "constant F_SETFL", "constant F_DUPFD_CLOEXEC", "constant O_NONBLOCK", "constant O_CLOEXEC",
        "constant O_RDWR", "constant O_CREAT",
        // file locks
        "constant LOCK_EX", "constant LOCK_NB",
        // error codes
        "constant EAGAIN", "constant EINTR", "constant EPIPE", "constant ECONNRESET", "constant EWOULDBLOCK",
    )
}

tasks.register("jextract") {
    group = "jextract"
    description = "Regenerates all C bindings of this module."
    dependsOn(jextractLibc)
}

// When regeneration and compilation run in one command, regenerate first.
// (Only an ordering rule: a normal build does not run jextract.)
tasks.matching { it.name.startsWith("compile") }.configureEach {
    mustRunAfter(jextractLibc)
}
