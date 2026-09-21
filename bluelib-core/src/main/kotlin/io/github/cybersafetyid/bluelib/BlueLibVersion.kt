package io.github.cybersafetyid.bluelib

/** Version information for the BlueLib artifacts. */
public object BlueLibVersion {
    public const val LIBRARY_NAME: String = "BlueLib"

    /**
     * Version of the published artifacts.
     *
     * Kept in source rather than generated so it is available without a `BuildConfig` (BlueLib ships
     * no generated code, which keeps R8 output and reproducible builds trivial). The release workflow
     * asserts that this value matches the version being published.
     */
    public const val VERSION: String = "0.1.0"

    /** API level this BlueLib release targets for its newest code paths. */
    public const val TARGET_API_LEVEL: Int = 37

    /** Oldest API level BlueLib supports. */
    public const val MIN_API_LEVEL: Int = 21
}
