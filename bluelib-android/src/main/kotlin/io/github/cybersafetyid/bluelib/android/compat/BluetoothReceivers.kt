package io.github.cybersafetyid.bluelib.android.compat

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

/**
 * Registers [receiver] for `android.bluetooth.*` broadcasts.
 *
 * These broadcasts are sent by the Bluetooth module (`com.android.bluetooth`, its own uid), not by the
 * system server, so a `RECEIVER_NOT_EXPORTED` receiver is silently left out of the recipient list:
 * bond results, discovery results and adapter changes never arrive. They are protected broadcasts that
 * only the stack can send, so exporting the receiver opens nothing to other apps.
 */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
internal fun Context.registerBluetoothReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (ApiLevel.requiresReceiverExportFlag()) {
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}
