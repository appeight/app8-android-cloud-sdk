package dev.app8.cloud

/**
 * Marks APIs that reach past the cloud SDK into the engine (iOS `@_spi(Advanced)`). Using the
 * engine directly can bypass caching, telemetry and fallbacks.
 */
@RequiresOptIn(message = "Reaches into the App8 engine underneath App8Cloud.", level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
public annotation class App8CloudAdvancedApi
