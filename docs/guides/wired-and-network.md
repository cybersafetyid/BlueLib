# TCP/IP, USB and serial links

BlueLib started as a Bluetooth library. The same messaging API now also runs over TCP/IP, USB serial
adapters and native UARTs, so an app that talks to a scale over Bluetooth today can talk to the same
scale over RS-232 tomorrow without changing its protocol code.

Every transport returns a `ByteConnection`: bytes in, bytes out. `createMessenger` wraps one with the
same `DataCodec` and `MessageFramer` support the Bluetooth messengers have.

```kotlin
val connection = blueLib.connectTcp("192.168.1.50", 9100).getOrThrow()
val messenger = blueLib.createMessenger(connection, framer = DelimiterFramer(byteArrayOf(0x0A)))

messenger.sendText("PING")
messenger.incomingText().collect { reply -> println(reply) }
```

| Transport | Facade call | Typical devices |
| --- | --- | --- |
| Bluetooth Classic | `connectRfcomm`, `connectL2cap` | SPP printers, OBD-II dongles, HC-05/HC-06 modules |
| TCP/IP (Wi-Fi, Ethernet/RJ45) | `connectTcp` | network printers (port 9100), Modbus TCP PLCs (502), serial device servers, ESP32 |
| USB serial (OTG) | `usbDevices`, `connectUsbSerial` | USB to RS-232/RS-485/TTL cables, Arduino, STM32, GPS receivers |
| Native UART | `openUart` | built-in RS-232/RS-485 ports of POS terminals, kiosks and industrial boards |

!!! warning "Collect before you write"
    `incoming` does not replay. Start collecting replies before sending the request that triggers them,
    or the first answer can be lost.

!!! warning "One framer per messenger"
    Framers buffer partial messages, and `close()` on a messenger resets its framer. Give every messenger
    its own framer: `DelimiterFramer.lineFeed()`, `crlf()` and `nullByte()` return a new instance on each
    call. Never store one framer in a field and pass it to two messengers, or they mix up their data.

## TCP/IP

```kotlin
val result = blueLib.connectTcp(
    host = "192.168.1.50",
    port = 9100,
    settings = TcpSettings(connectTimeoutMillis = 5_000, keepAlive = true, noDelay = true),
)
```

* The app needs `<uses-permission android:name="android.permission.INTERNET" />`. BlueLib does not add it
  to your manifest.
* Cleartext rules (`usesCleartextTraffic`) only apply to HTTP stacks, not to raw sockets.
* Failures come back as `LinkFailed` (refused, unreachable, DNS failure, timeout) or `OperationRejected`
  (invalid host or port).

### "Serial over RJ45"

An RJ45 jack on a serial device can mean two different things:

1. **Ethernet.** The device (or a *serial device server* such as Moxa NPort, USR-TCP232 or an ESP32 running
   `ser2net`) exposes its RS-232/RS-485 line as a TCP port. Use `connectTcp` with that port. Baud rate and
   parity are set in the device server's web UI, not by the app.
2. **RS-232 on an RJ45 connector** (Cisco-style console cables, some POS cash drawers and RS-485 Modbus
   wiring). This is still a serial line: use a USB serial adapter with the matching RJ45 cable and
   `connectUsbSerial`.

## USB serial

Android talks to USB devices in *host mode* (USB OTG). BlueLib ships its own drivers, so no root, no kernel
module and no third-party dependency is needed:

| Driver | Chips | Notes |
| --- | --- | --- |
| `CDC_ACM` | Arduino Uno/Mega, STM32 VCP, ESP32-S2/S3, WCH CH9102/CH343, most modems | USB standard class |
| `FTDI` | FT232R, FT230X, FT231X, FT232H/FT2232H (first port) | Up to 3 Mbaud |
| `CP210X` | CP2102, CP2104, CP2105/CP2108 (first port) | |
| `CH34X` | CH340, CH341 | Old chips (version < 0x30) are fixed at 8 data bits, no parity |
| `RAW_BULK` | Vendor devices and bidirectional USB printers with a bulk IN/OUT pair | No line settings |

Prolific PL2303 is not supported yet.

```kotlin
val device = blueLib.usbDevices().first { it.driver != null }

val connection = blueLib.connectUsbSerial(
    device = device,
    settings = SerialSettings(baudRate = 115_200, dataBits = 8, stopBits = StopBits.ONE, parity = Parity.NONE),
).getOrThrow()
```

* The first `connectUsbSerial` for a device shows the system "Allow access?" dialog. A denial comes back
  as `LinkFailed` with `the user denied USB access`.
* Declare `<uses-feature android:name="android.hardware.usb.host" android:required="false" />` so devices
  without host mode can still install the app.
* To skip the dialog and launch the app when a known device is plugged in, add an
  `android.hardware.usb.action.USB_DEVICE_ATTACHED` intent filter with a `device_filter.xml` listing the
  vendor and product ids. Android then grants access on attach.
* Driver detection uses the vendor id and the interface class. Pass `driver = UsbSerialDriver.CDC_ACM` (or
  another driver) to override it for clone chips.
* DTR and RTS are raised on open, because Arduino-class boards only start talking once DTR is set.
* Unplugging the cable closes the connection and reports `LinkFailed("device was unplugged")` on
  `diagnostics`.

## Native UART (built-in RS-232/RS-485)

POS terminals, kiosks, scales and industrial Android boards expose their serial ports as device files
such as `/dev/ttyS1`, `/dev/ttyS3` or `/dev/ttyMSM1`. Phones do not have such ports.

```kotlin
val connection = blueLib.openUart("/dev/ttyS3", SerialSettings(baudRate = 9600)).getOrThrow()
```

* The app must be able to read and write the device file. Stock images do not allow this; the board vendor
  grants it (a `chmod 666` in the init scripts plus an SELinux rule) or the app runs on a rooted image.
  Without access, `openUart` returns `LinkFailed` and is not retryable.
* Line settings are applied with the system `stty` tool. Pass `configure = false` when the vendor already
  configured the port or the image has no `stty`.
* Native UARTs support NONE/ODD/EVEN parity and 1 or 2 stop bits; MARK/SPACE parity and 1.5 stop bits are
  rejected with `OperationRejected`.

## RS-485

RS-485 is half duplex: only one side may transmit at a time. Use an adapter with automatic direction
control (almost all USB to RS-485 adapters have it) and send one request at a time, waiting for the reply.
Modbus RTU over RS-485 is the common case; its CRC-16 framing is not part of BlueLib yet, so build the
frame with `sendBytes` and validate the reply yourself.

## Testing

`bluelib-testing` ships `FakeByteConnection`, which records writes and lets a test push incoming bytes:

```kotlin
val connection = FakeByteConnection()
val messenger = StreamMessenger(connection, DelimiterFramer(byteArrayOf(0x0A)))

messenger.sendText("PING")
assertContentEquals("PING\n".toByteArray(), connection.written.single())

connection.receive("PONG\n".toByteArray())
assertEquals("PONG", messenger.incomingText().first())
```
