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
    libraries = emptyList()
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

// Shared settings of every jextract task.
fun JextractTask.common() {
    group = "jextract"
    jextractUrl = version("jextract-url")
    jextractSha256 = version("jextract-sha256")
    toolsDir = gradle.gradleUserHomeDir.resolve("caches/tessera/tools")
    outputDir = generatedDir
}

val jextractEgl = tasks.register<JextractTask>("jextractEgl") {
    common()
    description = "Generates EGL bindings."
    header = layout.projectDirectory.file("src/main/c/egl.h")
    targetPackage = "dev.tessera.ffi.c.egl"
    headerClassName = "Egl"
    libraries = listOf("libEGL.so.1")
    includes = listOf(
        "function eglGetPlatformDisplay", "function eglInitialize", "function eglTerminate",
        "function eglBindAPI", "function eglChooseConfig", "function eglCreateContext",
        "function eglDestroyContext", "function eglMakeCurrent", "function eglGetError",
        "function eglQueryString", "function eglGetProcAddress", "function eglReleaseThread",
        "constant EGL_PLATFORM_GBM_KHR", "constant EGL_OPENGL_ES_API", "constant EGL_NONE",
        "constant EGL_TRUE", "constant EGL_FALSE", "constant EGL_SUCCESS", "constant EGL_EXTENSIONS",
        "constant EGL_VENDOR", "constant EGL_VERSION", "constant EGL_CONTEXT_MAJOR_VERSION",
        "constant EGL_CONTEXT_MINOR_VERSION",
    )
}

val jextractGles = tasks.register<JextractTask>("jextractGles") {
    common()
    description = "Generates OpenGL ES 3 bindings."
    header = layout.projectDirectory.file("src/main/c/gles.h")
    targetPackage = "dev.tessera.ffi.c.gles"
    headerClassName = "Gles"
    libraries = listOf("libGLESv2.so.2")
    includes = listOf(
        // state and errors
        "function glGetError", "function glGetString", "function glEnable", "function glDisable",
        "function glBlendFunc", "function glViewport", "function glScissor", "function glClearColor",
        "function glClear", "function glFinish", "function glFlush", "function glPixelStorei",
        // textures
        "function glGenTextures", "function glDeleteTextures", "function glBindTexture",
        "function glTexImage2D", "function glTexSubImage2D", "function glTexParameteri",
        "function glActiveTexture",
        // framebuffers (drawing into textures)
        "function glGenFramebuffers", "function glDeleteFramebuffers", "function glBindFramebuffer",
        "function glFramebufferTexture2D", "function glCheckFramebufferStatus", "function glReadPixels",
        // shaders
        "function glCreateShader", "function glDeleteShader", "function glShaderSource",
        "function glCompileShader", "function glGetShaderiv", "function glGetShaderInfoLog",
        "function glCreateProgram", "function glDeleteProgram", "function glAttachShader",
        "function glLinkProgram", "function glGetProgramiv", "function glGetProgramInfoLog",
        "function glUseProgram", "function glGetUniformLocation", "function glGetAttribLocation",
        "function glUniform1i", "function glUniform1f", "function glUniform2f", "function glUniform4f",
        "function glUniformMatrix3fv",
        // vertex data
        "function glGenBuffers", "function glDeleteBuffers", "function glBindBuffer", "function glBufferData",
        "function glGenVertexArrays", "function glDeleteVertexArrays", "function glBindVertexArray",
        "function glVertexAttribPointer", "function glEnableVertexAttribArray", "function glDrawArrays",
        // constants
        "constant GL_NO_ERROR", "constant GL_VENDOR", "constant GL_RENDERER", "constant GL_VERSION",
        "constant GL_EXTENSIONS", "constant GL_BLEND", "constant GL_SCISSOR_TEST", "constant GL_ONE",
        "constant GL_ONE_MINUS_SRC_ALPHA", "constant GL_SRC_ALPHA", "constant GL_COLOR_BUFFER_BIT",
        "constant GL_TEXTURE_2D", "constant GL_TEXTURE0", "constant GL_RGBA", "constant GL_RGBA8",
        "constant GL_BGRA_EXT", "constant GL_UNSIGNED_BYTE", "constant GL_TEXTURE_MIN_FILTER",
        "constant GL_TEXTURE_MAG_FILTER", "constant GL_LINEAR", "constant GL_NEAREST",
        "constant GL_TEXTURE_WRAP_S", "constant GL_TEXTURE_WRAP_T", "constant GL_CLAMP_TO_EDGE",
        "constant GL_UNPACK_ROW_LENGTH_EXT", "constant GL_UNPACK_ROW_LENGTH", "constant GL_PACK_ALIGNMENT",
        "constant GL_UNPACK_ALIGNMENT", "constant GL_FRAMEBUFFER", "constant GL_COLOR_ATTACHMENT0",
        "constant GL_FRAMEBUFFER_COMPLETE", "constant GL_VERTEX_SHADER", "constant GL_FRAGMENT_SHADER",
        "constant GL_COMPILE_STATUS", "constant GL_LINK_STATUS", "constant GL_INFO_LOG_LENGTH",
        "constant GL_ARRAY_BUFFER", "constant GL_STATIC_DRAW", "constant GL_DYNAMIC_DRAW", "constant GL_FLOAT",
        "constant GL_FALSE", "constant GL_TRUE", "constant GL_TRIANGLE_STRIP", "constant GL_TRIANGLES",
    )
}

val jextractGbm = tasks.register<JextractTask>("jextractGbm") {
    common()
    description = "Generates GBM bindings."
    header = layout.projectDirectory.file("src/main/c/gbm.h")
    targetPackage = "dev.tessera.ffi.c.gbm"
    headerClassName = "Gbm"
    libraries = listOf("libgbm.so.1")
    includes = listOf(
        "function gbm_create_device", "function gbm_device_destroy", "function gbm_device_get_backend_name",
    )
}

val jextractXkb = tasks.register<JextractTask>("jextractXkb") {
    common()
    description = "Generates xkbcommon bindings."
    header = layout.projectDirectory.file("src/main/c/xkbcommon.h")
    targetPackage = "dev.tessera.ffi.c.xkb"
    headerClassName = "Xkb"
    libraries = listOf("libxkbcommon.so.0")
    includes = listOf(
        "struct xkb_rule_names",
        "function xkb_context_new", "function xkb_context_unref",
        "function xkb_keymap_new_from_names", "function xkb_keymap_get_as_string", "function xkb_keymap_unref",
        "function xkb_keymap_num_layouts",
        "function xkb_state_new", "function xkb_state_unref", "function xkb_state_update_key",
        "function xkb_state_serialize_mods", "function xkb_state_serialize_layout",
        "function xkb_state_key_get_one_sym", "function xkb_state_update_mask",
        "function xkb_keysym_get_name",
        "constant XKB_CONTEXT_NO_FLAGS", "constant XKB_KEYMAP_COMPILE_NO_FLAGS",
        "constant XKB_KEYMAP_FORMAT_TEXT_V1", "constant XKB_KEY_DOWN", "constant XKB_KEY_UP",
        "constant XKB_STATE_MODS_DEPRESSED", "constant XKB_STATE_MODS_LATCHED",
        "constant XKB_STATE_MODS_LOCKED", "constant XKB_STATE_LAYOUT_EFFECTIVE",
        "constant XKB_STATE_LAYOUT_LOCKED",
    )
}

tasks.register("jextract") {
    group = "jextract"
    description = "Regenerates all C bindings of this module."
    dependsOn(jextractLibc, jextractEgl, jextractGles, jextractGbm, jextractXkb)
}

// When regeneration and compilation run in one command, regenerate first.
// (Only an ordering rule: a normal build does not run jextract.)
tasks.matching { it.name.startsWith("compile") }.configureEach {
    mustRunAfter(jextractLibc, jextractEgl, jextractGles, jextractGbm, jextractXkb)
}
