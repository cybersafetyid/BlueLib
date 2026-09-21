package io.github.cybersafetyid.bluelib

/**
 * Marks API that is public only because Gradle modules cannot share `internal` visibility.
 *
 * Application code should use the `BlueLib` facade (`io.github.cybersafetyid.bluelib.BlueLib`)
 * instead of the platform adapters directly: the adapters follow the platform's shape, which
 * changes with almost every Android release, while the facade stays stable.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "BlueLib platform internals: prefer the BlueLib facade, which stays stable across Android releases.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class BlueLibInternal
