package dev.caulis.log

/**
 * Public only so that Caulis's own modules can share it (a native helper, the parts of the
 * configuration reader, the D-Bus client): not for your code — it may change or go in any
 * release. Using it anyway needs `@OptIn(InternalCaulisApi::class)`.
 */
@RequiresOptIn("Caulis's own internals: they may change in any release", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS, AnnotationTarget.CONSTRUCTOR)
annotation class InternalCaulisApi
