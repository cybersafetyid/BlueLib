package io.github.cybersafetyid.bluelib.android.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single threaded dispatcher used for every `android.bluetooth` call.
 *
 * Android's Bluetooth stack is not thread safe in the way most apps assume: callbacks are delivered
 * on the thread that started the operation, operations issued concurrently on the same `BluetoothGatt`
 * are dropped without an error, and some OEM stacks deadlock if two threads talk to the same
 * controller. Running every call on one dedicated thread removes that class of bugs, and makes the
 * `GattOperationQueue` trivially serial.
 */
public class PlatformDispatchers(
    name: String = "BlueLib-platform",
) : AutoCloseable {

    private val counter = AtomicInteger(1)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$name-${counter.getAndIncrement()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /** Dispatcher for platform calls and their callbacks. */
    public val bluetooth: CoroutineDispatcher = executor.asCoroutineDispatcher()

    override fun close() {
        executor.shutdownNow()
    }
}
