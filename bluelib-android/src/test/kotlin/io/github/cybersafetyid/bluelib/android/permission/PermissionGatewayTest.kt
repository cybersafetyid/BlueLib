package io.github.cybersafetyid.bluelib.android.permission

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.port.PermissionPort
import io.github.cybersafetyid.bluelib.port.PermissionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The permission gateway decides which failure a caller sees, and the distinction matters:
 *
 * * a permission the user denied is something the app should ask for again,
 * * a permission the app never declared is a manifest bug, and telling the user to "enable Bluetooth
 *   permission" only wastes their time.
 */
public class PermissionGatewayTest {

    private class FakePermissions(private val statuses: Map<String, PermissionStatus>) : PermissionPort {
        override fun statusOf(permission: String): PermissionStatus =
            statuses[permission] ?: PermissionStatus.NOT_APPLICABLE
    }

    private fun gateway(vararg statuses: Pair<String, PermissionStatus>) = PermissionGateway(
        port = FakePermissions(statuses.toMap()),
    ) { operation ->
        when (operation) {
            BluetoothOperation.SCAN -> listOf("android.permission.BLUETOOTH_SCAN")
            BluetoothOperation.CONNECT -> listOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
            )

            else -> listOf("android.permission.${operation.name}")
        }
    }

    @Test
    public fun `a granted permission satisfies the operation`() {
        val target = gateway("android.permission.BLUETOOTH_SCAN" to PermissionStatus.GRANTED)

        assertTrue(target.hasPermissionFor(BluetoothOperation.SCAN))
        assertTrue(target.requireOrFailure(BluetoothOperation.SCAN).isSuccess)
    }

    @Test
    public fun `a denied permission produces a retryable permission error naming the operation`() {
        val target = gateway("android.permission.BLUETOOTH_SCAN" to PermissionStatus.DENIED)

        val report = target.report(BluetoothOperation.SCAN)
        assertFalse(report.isSatisfied)
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), report.missing)

        val error = target.requireOrFailure(BluetoothOperation.SCAN).errorOrNull()
        assertTrue(error is BlueLibError.PermissionMissing)
        val typed = error as BlueLibError.PermissionMissing
        assertEquals("scan", typed.operation)
        assertTrue(typed.isRetryable)
        assertFalse(typed.permanentlyDenied)
    }

    @Test
    public fun `a permanently denied permission is flagged so a UI can send the user to settings`() {
        val target = gateway("android.permission.BLUETOOTH_SCAN" to PermissionStatus.DENIED_PERMANENTLY)

        val typed = target.requireOrFailure(BluetoothOperation.SCAN).errorOrNull() as BlueLibError.PermissionMissing

        assertTrue(typed.permanentlyDenied)
    }

    @Test
    public fun `a permission the app never declared is reported as a manifest problem`() {
        // NOT_APPLICABLE means the manifest does not request it at all.
        val target = gateway("android.permission.BLUETOOTH_SCAN" to PermissionStatus.NOT_APPLICABLE)

        val error = target.requireOrFailure(BluetoothOperation.SCAN).errorOrNull()

        assertTrue(error is BlueLibError.OperationRejected, "an undeclared permission is not a user decision")
        assertTrue(error!!.message.contains("android.permission.BLUETOOTH_SCAN"))
    }

    @Test
    public fun `a missing permission is reported even when another one is undeclared`() {
        val target = gateway(
            "android.permission.BLUETOOTH_CONNECT" to PermissionStatus.DENIED,
            "android.permission.BLUETOOTH_SCAN" to PermissionStatus.NOT_APPLICABLE,
        )

        val error = target.requireOrFailure(BluetoothOperation.CONNECT).errorOrNull()

        assertTrue(error is BlueLibError.PermissionMissing)
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), (error as BlueLibError.PermissionMissing).permissions)
    }

    @Test
    public fun `every missing permission ends up in the report`() {
        val target = gateway(
            "android.permission.BLUETOOTH_CONNECT" to PermissionStatus.DENIED,
            "android.permission.BLUETOOTH_SCAN" to PermissionStatus.DENIED_PERMANENTLY,
        )

        val report = target.report(BluetoothOperation.CONNECT)

        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"),
            report.missing,
        )
        val typed = target.requireOrFailure(BluetoothOperation.CONNECT).errorOrNull() as BlueLibError.PermissionMissing
        assertTrue(typed.permanentlyDenied, "one permanent denial is enough to send the user to settings")
    }
}
