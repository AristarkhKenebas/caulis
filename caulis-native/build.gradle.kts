// The only module that talks to C libraries directly (through the FFM API).
// Everything above it uses safe Kotlin classes from here.

plugins {
    id("caulis.library")
}

val libs = versionCatalogs.named("libs")
fun version(name: String) = libs.findVersion(name).get().requiredVersion

// Generated bindings are kept in the repository, so a normal build needs neither
// jextract nor C headers. Regenerate with: ./gradlew :caulis-native:jextract
val generatedDir = layout.projectDirectory.dir("src/generated/java")

sourceSets.main {
    java.srcDir(generatedDir)
}

val jextractLibc = tasks.register<JextractTask>("jextractLibc") {
    group = "jextract"
    description = "Generates struct layouts and constants of the C library."
    libraries = emptyList()
    header = layout.projectDirectory.file("src/main/c/libc.h")
    targetPackage = "dev.caulis.ffi.c.libc"
    headerClassName = "LibC"
    jextractUrl = version("jextract-url")
    jextractSha256 = version("jextract-sha256")
    toolsDir = gradle.gradleUserHomeDir.resolve("caches/caulis/tools")
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
        "constant EMFILE", "constant ENFILE", "constant ECONNABORTED",
    )
}

// Shared settings of every jextract task.
fun JextractTask.common() {
    group = "jextract"
    jextractUrl = version("jextract-url")
    jextractSha256 = version("jextract-sha256")
    toolsDir = gradle.gradleUserHomeDir.resolve("caches/caulis/tools")
    outputDir = generatedDir
}

val jextractEgl = tasks.register<JextractTask>("jextractEgl") {
    common()
    description = "Generates EGL bindings."
    header = layout.projectDirectory.file("src/main/c/egl.h")
    targetPackage = "dev.caulis.ffi.c.egl"
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
        // GPU buffers shared with other programs and the display (dma-buf)
        "function eglCreateImage", "function eglDestroyImage",
        "constant EGL_LINUX_DMA_BUF_EXT", "constant EGL_WIDTH", "constant EGL_HEIGHT",
        "constant EGL_LINUX_DRM_FOURCC_EXT", "constant EGL_IMAGE_PRESERVED_KHR",
        "constant EGL_DMA_BUF_PLANE0_FD_EXT", "constant EGL_DMA_BUF_PLANE0_OFFSET_EXT",
        "constant EGL_DMA_BUF_PLANE0_PITCH_EXT", "constant EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT",
        "constant EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT",
        "constant EGL_DMA_BUF_PLANE1_FD_EXT", "constant EGL_DMA_BUF_PLANE1_OFFSET_EXT",
        "constant EGL_DMA_BUF_PLANE1_PITCH_EXT", "constant EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT",
        "constant EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT",
        "constant EGL_DMA_BUF_PLANE2_FD_EXT", "constant EGL_DMA_BUF_PLANE2_OFFSET_EXT",
        "constant EGL_DMA_BUF_PLANE2_PITCH_EXT", "constant EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT",
        "constant EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT",
        "constant EGL_DMA_BUF_PLANE3_FD_EXT", "constant EGL_DMA_BUF_PLANE3_OFFSET_EXT",
        "constant EGL_DMA_BUF_PLANE3_PITCH_EXT", "constant EGL_DMA_BUF_PLANE3_MODIFIER_LO_EXT",
        "constant EGL_DMA_BUF_PLANE3_MODIFIER_HI_EXT",
    )
}

val jextractGles = tasks.register<JextractTask>("jextractGles") {
    common()
    description = "Generates OpenGL ES 3 bindings."
    header = layout.projectDirectory.file("src/main/c/gles.h")
    targetPackage = "dev.caulis.ffi.c.gles"
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
    targetPackage = "dev.caulis.ffi.c.gbm"
    headerClassName = "Gbm"
    libraries = listOf("libgbm.so.1")
    includes = listOf(
        "function gbm_create_device", "function gbm_device_destroy", "function gbm_device_get_backend_name",
        "function gbm_device_is_format_supported",
        "function gbm_bo_create", "function gbm_bo_create_with_modifiers2", "function gbm_bo_destroy",
        "function gbm_bo_get_width", "function gbm_bo_get_height", "function gbm_bo_get_format",
        "function gbm_bo_get_modifier", "function gbm_bo_get_plane_count", "function gbm_bo_get_fd_for_plane",
        "function gbm_bo_get_stride_for_plane", "function gbm_bo_get_offset", "function gbm_bo_get_handle_for_plane",
        "union gbm_bo_handle",
        "constant GBM_BO_USE_SCANOUT", "constant GBM_BO_USE_RENDERING", "constant GBM_BO_USE_LINEAR",
        "constant GBM_FORMAT_XRGB8888", "constant GBM_FORMAT_ARGB8888",
    )
}

val jextractXkb = tasks.register<JextractTask>("jextractXkb") {
    common()
    description = "Generates xkbcommon bindings."
    header = layout.projectDirectory.file("src/main/c/xkbcommon.h")
    targetPackage = "dev.caulis.ffi.c.xkb"
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

val jextractDrm = tasks.register<JextractTask>("jextractDrm") {
    common()
    description = "Generates libdrm (kernel mode setting) bindings."
    header = layout.projectDirectory.file("src/main/c/kms.h")
    includeDirs = listOf("/usr/include/libdrm")
    targetPackage = "dev.caulis.ffi.c.drm"
    headerClassName = "Drm"
    libraries = listOf("libdrm.so.2")
    includes = listOf(
        "struct _drmModeRes", "typedef drmModeRes", "struct _drmModeConnector", "typedef drmModeConnector",
        "struct _drmModeModeInfo", "typedef drmModeModeInfo", "struct _drmModeEncoder", "typedef drmModeEncoder",
        "struct _drmModeCrtc", "typedef drmModeCrtc", "struct _drmModeObjectProperties",
        "typedef drmModeObjectProperties", "struct _drmModeProperty", "typedef drmModePropertyRes", "struct drm_mode_property_enum",
        "struct _drmModePlane", "typedef drmModePlane", "struct _drmModePlaneRes", "typedef drmModePlaneRes",
        "struct _drmEventContext", "typedef drmEventContext",
        "function drmModeGetResources", "function drmModeFreeResources", "function drmModeGetConnector",
        "function drmModeFreeConnector", "function drmModeGetEncoder", "function drmModeFreeEncoder",
        "function drmModeGetCrtc", "function drmModeFreeCrtc", "function drmModeObjectGetProperties",
        "function drmModeFreeObjectProperties", "function drmModeGetProperty", "function drmModeFreeProperty",
        "function drmModeGetPlaneResources", "function drmModeFreePlaneResources", "function drmModeGetPlane",
        "function drmModeFreePlane", "function drmModeAtomicAlloc", "function drmModeAtomicFree",
        "function drmModeAtomicAddProperty", "function drmModeAtomicCommit", "function drmModeCreatePropertyBlob",
        "function drmModeDestroyPropertyBlob", "function drmModeAddFB2WithModifiers", "function drmModeRmFB",
        "function drmSetClientCap", "function drmGetCap", "function drmHandleEvent",
        "function drmGetRenderDeviceNameFromFd", "function drmDropMaster",
        "struct _drmModePropertyBlob", "typedef drmModePropertyBlobRes",
        "function drmModeGetPropertyBlob", "function drmModeFreePropertyBlob",
        "function drmModeConnectorGetPossibleCrtcs",
        // Showing a program's own GPU buffer on screen (direct scanout).
        "function drmPrimeFDToHandle", "function drmCloseBufferHandle",
        "constant DRM_CLIENT_CAP_ATOMIC", "constant DRM_CLIENT_CAP_UNIVERSAL_PLANES",
        "constant DRM_CAP_TIMESTAMP_MONOTONIC", "constant DRM_CAP_ADDFB2_MODIFIERS",
        "constant DRM_MODE_CONNECTED", "constant DRM_MODE_ATOMIC_ALLOW_MODESET",
        "constant DRM_MODE_ATOMIC_NONBLOCK", "constant DRM_MODE_ATOMIC_TEST_ONLY", "constant DRM_MODE_PAGE_FLIP_EVENT",
        "constant DRM_MODE_OBJECT_CONNECTOR", "constant DRM_MODE_OBJECT_CRTC", "constant DRM_MODE_OBJECT_PLANE",
        "constant DRM_PLANE_TYPE_PRIMARY", "constant DRM_PLANE_TYPE_CURSOR", "constant DRM_PLANE_TYPE_OVERLAY",
        "constant DRM_MODE_TYPE_PREFERRED", "constant DRM_EVENT_CONTEXT_VERSION", "constant DRM_MODE_FB_MODIFIERS",
        "constant DRM_FORMAT_XRGB8888", "constant DRM_FORMAT_ARGB8888", "constant DRM_FORMAT_XBGR8888",
        "constant DRM_FORMAT_ABGR8888",
        "constant DRM_MODE_CONNECTOR_eDP", "constant DRM_MODE_CONNECTOR_HDMIA", "constant DRM_MODE_CONNECTOR_DisplayPort",
        "constant DRM_MODE_CONNECTOR_VGA", "constant DRM_MODE_CONNECTOR_DVII", "constant DRM_MODE_CONNECTOR_DVID",
        "constant DRM_MODE_CONNECTOR_LVDS", "constant DRM_MODE_CONNECTOR_USB", "constant DRM_MODE_CONNECTOR_VIRTUAL",
    )
}

val jextractSeat = tasks.register<JextractTask>("jextractSeat") {
    common()
    description = "Generates libseat bindings."
    header = layout.projectDirectory.file("src/main/c/seat.h")
    targetPackage = "dev.caulis.ffi.c.seat"
    headerClassName = "Seat"
    libraries = listOf("libseat.so.1")
    includes = listOf(
        "struct libseat_seat_listener",
        "function libseat_open_seat", "function libseat_close_seat", "function libseat_open_device",
        "function libseat_close_device", "function libseat_get_fd", "function libseat_dispatch",
        "function libseat_disable_seat", "function libseat_switch_session", "function libseat_seat_name",
        "function libseat_set_log_level",
        "constant LIBSEAT_LOG_LEVEL_SILENT", "constant LIBSEAT_LOG_LEVEL_ERROR", "constant LIBSEAT_LOG_LEVEL_INFO",
    )
}

val jextractInput = tasks.register<JextractTask>("jextractInput") {
    common()
    description = "Generates libinput and libudev bindings."
    header = layout.projectDirectory.file("src/main/c/input.h")
    targetPackage = "dev.caulis.ffi.c.input"
    headerClassName = "Input"
    libraries = listOf("libinput.so.10", "libudev.so.1")
    includes = listOf(
        "struct libinput_interface",
        "function udev_new", "function udev_unref",
        "function libinput_udev_create_context", "function libinput_udev_assign_seat", "function libinput_unref",
        "function libinput_get_fd", "function libinput_dispatch", "function libinput_get_event",
        "function libinput_suspend", "function libinput_resume",
        "function libinput_event_get_type", "function libinput_event_destroy", "function libinput_event_get_device",
        "function libinput_event_get_keyboard_event", "function libinput_event_keyboard_get_key",
        "function libinput_event_keyboard_get_key_state", "function libinput_event_keyboard_get_time",
        "function libinput_event_get_pointer_event", "function libinput_event_pointer_get_time",
        "function libinput_event_pointer_get_dx", "function libinput_event_pointer_get_dy",
        "function libinput_event_pointer_get_absolute_x_transformed",
        "function libinput_event_pointer_get_absolute_y_transformed",
        "function libinput_event_pointer_get_button", "function libinput_event_pointer_get_button_state",
        "function libinput_event_pointer_has_axis", "function libinput_event_pointer_get_scroll_value",
        "function libinput_event_pointer_get_scroll_value_v120",
        "function libinput_device_get_name", "function libinput_device_config_tap_set_enabled",
        "function libinput_device_config_tap_get_finger_count",
        "function libinput_device_config_scroll_set_natural_scroll_enabled",
        "function libinput_device_config_scroll_has_natural_scroll",
        "constant LIBINPUT_EVENT_DEVICE_ADDED", "constant LIBINPUT_EVENT_DEVICE_REMOVED",
        "constant LIBINPUT_EVENT_KEYBOARD_KEY", "constant LIBINPUT_EVENT_POINTER_MOTION",
        "constant LIBINPUT_EVENT_POINTER_MOTION_ABSOLUTE", "constant LIBINPUT_EVENT_POINTER_BUTTON",
        "constant LIBINPUT_EVENT_POINTER_SCROLL_WHEEL", "constant LIBINPUT_EVENT_POINTER_SCROLL_FINGER",
        "constant LIBINPUT_EVENT_POINTER_SCROLL_CONTINUOUS",
        "constant LIBINPUT_KEY_STATE_PRESSED", "constant LIBINPUT_BUTTON_STATE_PRESSED",
        "constant LIBINPUT_POINTER_AXIS_SCROLL_VERTICAL", "constant LIBINPUT_POINTER_AXIS_SCROLL_HORIZONTAL",
        "constant LIBINPUT_CONFIG_TAP_ENABLED",
        "function libinput_device_has_capability",
        "constant LIBINPUT_DEVICE_CAP_KEYBOARD", "constant LIBINPUT_DEVICE_CAP_POINTER",
        // udev monitor: notices monitors being plugged in or out (DRM "hotplug" events).
        "function udev_monitor_new_from_netlink", "function udev_monitor_filter_add_match_subsystem_devtype",
        "function udev_monitor_enable_receiving", "function udev_monitor_get_fd",
        "function udev_monitor_receive_device", "function udev_monitor_unref",
        "function udev_device_get_devnum", "function udev_device_get_property_value",
        "function udev_device_get_action", "function udev_device_unref",
    )
}

tasks.register("jextract") {
    group = "jextract"
    description = "Regenerates all C bindings of this module."
    dependsOn(jextractLibc, jextractEgl, jextractGles, jextractGbm, jextractXkb, jextractDrm, jextractSeat, jextractInput)
}

// When regeneration and compilation run in one command, regenerate first.
// (Only an ordering rule: a normal build does not run jextract.)
tasks.matching { it.name.startsWith("compile") }.configureEach {
    mustRunAfter(jextractLibc, jextractEgl, jextractGles, jextractGbm, jextractXkb, jextractDrm, jextractSeat, jextractInput)
}
