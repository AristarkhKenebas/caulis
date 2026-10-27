package dev.caulis.wm

/** Which programs may take the keyboard by themselves (see [Caulis.focusStealing]). */
enum class FocusStealing {
    /** Only while one of its own windows has the keyboard. */
    SAME_PROGRAM,

    /** Always. */
    ALWAYS,

    /** Never: the window is marked urgent instead. */
    NEVER,
}

/** Which keys are modifiers in bindings (see [Caulis.bindingModifiers]). */
enum class BindingModifiers {
    /** As the keyboard layout and its options say. */
    XKB,

    /** The keys printed Ctrl, Alt, Super and Shift. */
    PHYSICAL,
}
