// X11 programs inside a Caulis compositor: starts Xwayland when the first one connects and
// is the window manager of their windows (through XCB), so they are windows like any other.

plugins {
    id("caulis.library")
}

dependencies {
    api(project(":caulis-core"))
    testImplementation(testFixtures(project(":caulis-core")))
}

val libs = versionCatalogs.named("libs")
fun version(name: String) = libs.findVersion(name).get().requiredVersion

val generatedDir = layout.projectDirectory.dir("src/generated/java")

sourceSets.main {
    java.srcDir(generatedDir)
}

// Generated code is kept in the repository; regenerate with ./gradlew :caulis-xwayland:jextractXcb
tasks.register<JextractTask>("jextractXcb") {
    group = "jextract"
    description = "Generates XCB bindings (core protocol, Composite, XFixes)."
    jextractUrl = version("jextract-url")
    jextractSha256 = version("jextract-sha256")
    toolsDir = gradle.gradleUserHomeDir.resolve("caches/caulis/tools")
    outputDir = generatedDir
    header = layout.projectDirectory.file("src/main/c/xcb.h")
    targetPackage = "dev.caulis.ffi.c.xcb"
    headerClassName = "Xcb"
    libraries = listOf("libxcb.so.1", "libxcb-composite.so.0", "libxcb-xfixes.so.0", "libxcb-render.so.0")
    includes = listOf(
        // the connection
        "function xcb_connect_to_fd", "function xcb_disconnect", "function xcb_connection_has_error",
        "function xcb_get_setup", "function xcb_setup_roots_iterator", "function xcb_get_file_descriptor",
        "function xcb_flush", "function xcb_poll_for_event", "function xcb_generate_id", "function xcb_request_check",
        "function xcb_get_extension_data", "function xcb_prefetch_extension_data",
        "struct xcb_setup_t", "struct xcb_screen_t", "struct xcb_screen_iterator_t",
        "typedef xcb_generic_event_t", "typedef xcb_generic_error_t", "typedef xcb_void_cookie_t",
        "struct xcb_query_extension_reply_t",
        // atoms and properties
        "function xcb_intern_atom", "function xcb_intern_atom_reply", "struct xcb_intern_atom_cookie_t", "struct xcb_intern_atom_reply_t",
        "function xcb_get_property", "function xcb_get_property_reply", "function xcb_get_property_value",
        "function xcb_get_property_value_length", "struct xcb_get_property_cookie_t", "struct xcb_get_property_reply_t",
        "function xcb_change_property", "function xcb_delete_property",
        "function xcb_get_atom_name", "function xcb_get_atom_name_reply", "function xcb_get_atom_name_name",
        "function xcb_get_atom_name_name_length", "struct xcb_get_atom_name_cookie_t", "struct xcb_get_atom_name_reply_t",
        // windows
        "function xcb_create_window", "function xcb_destroy_window", "function xcb_map_window", "function xcb_unmap_window",
        "function xcb_configure_window", "function xcb_change_window_attributes", "function xcb_change_window_attributes_checked",
        "function xcb_set_input_focus", "function xcb_send_event", "function xcb_kill_client",
        "function xcb_get_geometry", "function xcb_get_geometry_reply", "struct xcb_get_geometry_cookie_t", "struct xcb_get_geometry_reply_t",
        "function xcb_get_window_attributes", "function xcb_get_window_attributes_reply",
        "struct xcb_get_window_attributes_cookie_t", "struct xcb_get_window_attributes_reply_t",
        // selections (the clipboard)
        "function xcb_set_selection_owner", "function xcb_convert_selection",
        // events a window manager handles
        "struct xcb_create_notify_event_t", "struct xcb_destroy_notify_event_t", "struct xcb_map_request_event_t",
        "struct xcb_map_notify_event_t", "struct xcb_unmap_notify_event_t", "struct xcb_configure_request_event_t",
        "struct xcb_configure_notify_event_t", "struct xcb_property_notify_event_t", "struct xcb_client_message_event_t",
        "union xcb_client_message_data_t", "struct xcb_focus_in_event_t", "struct xcb_selection_request_event_t",
        "struct xcb_selection_notify_event_t", "struct xcb_selection_clear_event_t",
        // Composite: each window draws into its own buffer (Xwayland needs this when rootless)
        "function xcb_composite_redirect_subwindows", "var xcb_composite_id",
        // XFixes: being told when another program takes the clipboard
        "function xcb_xfixes_query_version", "function xcb_xfixes_query_version_reply", "function xcb_xfixes_select_selection_input",
        "struct xcb_xfixes_query_version_cookie_t", "struct xcb_xfixes_query_version_reply_t",
        "struct xcb_xfixes_selection_notify_event_t", "var xcb_xfixes_id", "function xcb_xfixes_set_client_disconnect_mode",
        // RENDER: the cursor of the root window, from the compositor's cursor theme
        "function xcb_render_query_pict_formats", "function xcb_render_query_pict_formats_reply",
        "function xcb_render_query_pict_formats_formats", "function xcb_render_query_pict_formats_formats_length",
        "struct xcb_render_query_pict_formats_cookie_t", "struct xcb_render_query_pict_formats_reply_t",
        "struct xcb_render_pictforminfo_t", "struct xcb_render_directformat_t",
        "function xcb_render_create_picture", "function xcb_render_create_cursor", "function xcb_render_free_picture",
        "function xcb_create_pixmap", "function xcb_free_pixmap", "function xcb_create_gc", "function xcb_free_gc", "function xcb_put_image", "function xcb_free_cursor",
        // constants
        "constant XCB_NONE", "constant XCB_CURRENT_TIME", "constant XCB_COPY_FROM_PARENT", "constant XCB_WINDOW_NONE",
        "constant XCB_CREATE_NOTIFY", "constant XCB_DESTROY_NOTIFY", "constant XCB_MAP_REQUEST", "constant XCB_MAP_NOTIFY",
        "constant XCB_UNMAP_NOTIFY", "constant XCB_CONFIGURE_REQUEST", "constant XCB_CONFIGURE_NOTIFY",
        "constant XCB_PROPERTY_NOTIFY", "constant XCB_CLIENT_MESSAGE", "constant XCB_FOCUS_IN", "constant XCB_SELECTION_REQUEST",
        "constant XCB_SELECTION_NOTIFY", "constant XCB_SELECTION_CLEAR", "constant XCB_XFIXES_SELECTION_NOTIFY",
        "constant XCB_CW_EVENT_MASK", "constant XCB_CW_OVERRIDE_REDIRECT", "constant XCB_CW_BORDER_PIXEL",
        "constant XCB_EVENT_MASK_SUBSTRUCTURE_REDIRECT", "constant XCB_EVENT_MASK_SUBSTRUCTURE_NOTIFY",
        "constant XCB_EVENT_MASK_PROPERTY_CHANGE", "constant XCB_EVENT_MASK_FOCUS_CHANGE", "constant XCB_EVENT_MASK_NO_EVENT",
        "constant XCB_CONFIG_WINDOW_X", "constant XCB_CONFIG_WINDOW_Y", "constant XCB_CONFIG_WINDOW_WIDTH",
        "constant XCB_CONFIG_WINDOW_HEIGHT", "constant XCB_CONFIG_WINDOW_BORDER_WIDTH", "constant XCB_CONFIG_WINDOW_STACK_MODE",
        "constant XCB_CONFIG_WINDOW_SIBLING", "constant XCB_STACK_MODE_ABOVE", "constant XCB_STACK_MODE_BELOW",
        "constant XCB_PROP_MODE_REPLACE", "constant XCB_PROP_MODE_APPEND", "constant XCB_INPUT_FOCUS_POINTER_ROOT",
        "constant XCB_WINDOW_CLASS_INPUT_OUTPUT", "constant XCB_WINDOW_CLASS_INPUT_ONLY", "constant XCB_COMPOSITE_REDIRECT_MANUAL",
        "constant XCB_ATOM_ATOM", "constant XCB_ATOM_CARDINAL", "constant XCB_ATOM_STRING", "constant XCB_ATOM_WINDOW",
        "constant XCB_ATOM_WM_NAME", "constant XCB_ATOM_WM_CLASS", "constant XCB_ATOM_WM_HINTS", "constant XCB_ATOM_WM_NORMAL_HINTS",
        "constant XCB_ATOM_WM_TRANSIENT_FOR", "constant XCB_ATOM_PRIMARY", "constant XCB_GET_PROPERTY_TYPE_ANY",
        "constant XCB_XFIXES_SELECTION_EVENT_MASK_SET_SELECTION_OWNER", "constant XCB_XFIXES_SELECTION_EVENT_MASK_SELECTION_WINDOW_DESTROY",
        "constant XCB_XFIXES_SELECTION_EVENT_MASK_SELECTION_CLIENT_CLOSE", "constant XCB_SEND_EVENT_DEST_POINTER_WINDOW",
        "constant XCB_EVENT_MASK_STRUCTURE_NOTIFY", "constant XCB_ATOM_INTEGER", "constant XCB_PROPERTY_DELETE",
        "constant XCB_PROPERTY_NEW_VALUE", "constant XCB_XFIXES_CLIENT_DISCONNECT_FLAGS_TERMINATE",
        "constant XCB_NOTIFY_MODE_GRAB", "constant XCB_NOTIFY_MODE_UNGRAB", "constant XCB_NOTIFY_DETAIL_POINTER",
        "constant XCB_CW_CURSOR", "constant XCB_IMAGE_FORMAT_Z_PIXMAP", "constant XCB_RENDER_PICT_TYPE_DIRECT",
    )
}
