package io.github.cybersafetyid.bluelib.domain

/**
 * Platform features BlueLib can detect and reason about, together with the API level that
 * introduced them.
 *
 * A single source of truth for "what can this device do" keeps the version checks in one place and
 * lets the DSL expose a stable enum to applications instead of `int` constants.
 *
 * Values are verified against the Android SDK `api-versions.xml` database; run
 * `./gradlew generateCompatibilityMatrix` to refresh the generated matrix in
 * `docs/compatibility-matrix.md`.
 */
public enum class BluetoothFeature(
    /** API level that introduced the feature. */
    public val introducedInApiLevel: Int,
    /** Short explanation used in errors and documentation. */
    public val description: String,
    /** A minor API level inside the same major release, e.g. `1` for Android 16.1 (API 36.1). */
    public val introducedInMinorApiLevel: Int = 0,
) {
    /** Bluetooth Classic (BR/EDR) and RFCOMM sockets. */
    BLUETOOTH_CLASSIC(5, "Bluetooth Classic discovery, bonding and RFCOMM/L2CAP sockets."),

    /** Bluetooth Low Energy. */
    LOW_ENERGY(18, "Bluetooth Low Energy scanning, advertising and GATT."),

    /** LE peripheral role: multiple simultaneous connections and one advertising set. */
    LE_PERIPHERAL_ROLE(21, "Device can act as an LE peripheral (advertiser plus GATT server)."),

    /** More than one simultaneous advertising set. */
    MULTIPLE_ADVERTISEMENT(21, "Controller supports several concurrent advertising sets."),

    /** Offloaded advertising data filtering. */
    OFFLOADED_FILTERING(21, "Controller can filter scan results without waking the host."),

    /** Offloaded scan batching. */
    OFFLOADED_BATCHING(21, "Controller can batch scan results."),

    /** Extended scanning and advertising. */
    LE_EXTENDED_ADVERTISING(26, "Extended advertising (up to ~1650 byte payloads) and extended scanning."),

    /** 2M PHY. */
    LE_2M_PHY(26, "2 Mbit/s LE PHY."),

    /** Coded PHY (long range). */
    LE_CODED_PHY(26, "Coded PHY (S=2, S=8) for long range."),

    /** Periodic advertising and periodic sync. */
    LE_PERIODIC_ADVERTISING(26, "Periodic advertising trains and synchronisation."),

    /** Scan result batching through a `PendingIntent` (background scanning without a foreground service). */
    OFFLOADED_PENDING_INTENT_SCAN(26, "PendingIntent based scan delivery for background use."),

    /** Hearing Aid Profile. */
    HEARING_AID_PROFILE(28, "Bluetooth Hearing Aid Profile (ASHA)."),

    /** HID device role. */
    HID_DEVICE_ROLE(28, "Act as a Bluetooth HID device (keyboard, mouse, gamepad)."),

    /** L2CAP CoC sockets. */
    L2CAP_CHANNEL(29, "LE Credit Based Flow Control L2CAP channels."),

    /** LE Audio: built into Android 13 (API 33), where the support queries went public. */
    LE_AUDIO(33, "LE Audio support (LC3 codec, unicast)."),

    /** Auracast broadcast source support (capability flag, controlling broadcasts needs a system app). */
    LE_AUDIO_BROADCAST_SOURCE(33, "Hardware supports being an LE Audio broadcast (Auracast) source."),

    /** Auracast broadcast assistant support. */
    LE_AUDIO_BROADCAST_ASSISTANT(33, "Hardware supports the Auracast broadcast assistant role."),

    /** Coordinated set identification profile (true wireless earbuds). */
    CSIP_SET_COORDINATOR(33, "Coordinated Set Identification Profile set coordinator."),

    /** Discoverable advertising sets. */
    ADVERTISING_SET_DISCOVERABLE(34, "Advertising sets can be marked discoverable."),

    /** DCK connection priority. */
    CONNECTION_PRIORITY_DCK(34, "Distributed connection kit connection priority."),

    /** Bluetooth LE Channel Sounding, exposed through the Ranging module. */
    LE_CHANNEL_SOUNDING(36, "Channel Sounding based ranging (Bluetooth 6.0 feature)."),

    /** Bluetooth RSSI ranging through the Ranging module. */
    LE_RSSI_RANGING(36, "RSSI based LE ranging through the Ranging module."),

    /** Typed bond status reporting. */
    BOND_STATUS_TYPED(37, "Typed bond status with pairing algorithm and variant."),

    /** Connection subrating (BLE 5.3). */
    CONNECTION_SUBRATING(36, "Connection subrating for lower power links.", introducedInMinorApiLevel = 1),

    /** Socket settings for RFCOMM/L2CAP creation. */
    SOCKET_SETTINGS(36, "Create RFCOMM/L2CAP sockets from a BluetoothSocketSettings object."),

    /** High data throughput PHY (Bluetooth 6.x). */
    LE_HDT_PHY(37, "High data throughput LE PHY."),

    /** GATT connection settings object (Android 17). */
    GATT_CONNECTION_SETTINGS(37, "Connect over GATT using BluetoothGattConnectionSettings."),
    ;

    /** Full name used in errors and documentation. */
    public val fullName: String
        get() = if (introducedInMinorApiLevel == 0) {
            name
        } else {
            "$name (API $introducedInApiLevel.$introducedInMinorApiLevel)"
        }
}

/** A detected capability: the feature plus the API level the running device reports. */
public data class Capability(
    val feature: BluetoothFeature,
    val supported: Boolean,
    val currentApiLevel: Int,
) {
    /** `true` when the feature exists in the platform, regardless of hardware support. */
    public val availableInPlatform: Boolean
        get() = currentApiLevel >= feature.introducedInApiLevel
}
