package dev.caulis.screenshot

/** The kind of file a screenshot is saved as. */
enum class ShotFormat(
    /** The file's ending. */
    val extension: String,
) {
    /** Exactly as on screen, see-through parts kept. */
    PNG("png"),

    /** Smaller, a little blurred ([Screenshots.jpegQuality]); see-through parts on white. */
    JPEG("jpg");

    companion object {
        /** The format named [name] ("png", "jpg", "jpeg"; any case), or null if there is none of that name. */
        @JvmStatic
        fun parse(name: String): ShotFormat? = when (name.trim().lowercase()) {
            "png" -> PNG
            "jpg", "jpeg" -> JPEG
            else -> null
        }
    }
}
