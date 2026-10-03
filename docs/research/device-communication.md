# Device-to-device communication on Android

Research note behind BlueLib's move from "Bluetooth library" to "device communication library". It lists
every way an Android app can exchange data with another device, what the platform requires for each, and
where BlueLib stands.

Status legend: **Shipped** in BlueLib, **Planned** (fits the `ByteConnection` model, on the roadmap),
**Out of scope** (needs Google Play services, system privileges, or is not a data link).

## 1. Wired

| Link | Android API | Requirements | Status |
| --- | --- | --- | --- |
| USB serial (CDC-ACM, FTDI, CP210x, CH34x) | `UsbManager` host mode, bulk + control transfers | USB OTG, per-device user consent | **Shipped** |
| USB raw bulk (vendor class, printer class) | `UsbManager` | Bulk IN/OUT endpoint pair | **Shipped** |
| USB serial PL2303 | `UsbManager` | Vendor init sequence, several chip revisions | Planned |
| USB HID (barcode scanners in HID-POS mode, custom HID) | `UsbManager` interrupt endpoints | HID report parsing | Planned |
| USB accessory (AOA) | `UsbManager.openAccessory` | Accessory acts as USB host; firmware must speak AOA | Planned |
| Native UART `/dev/tty*` (RS-232/RS-485/TTL) | `java.io` file streams + `stty` | Vendor or rooted image grants device file access | **Shipped** |
| RS-485 half duplex | Same as serial | Adapter with automatic direction control | Shipped via USB/UART; manual RTS direction not supported |
| Ethernet (RJ45) | Same as TCP/IP below | USB Ethernet adapter or built-in port | **Shipped** (through TCP) |
| GPIO, I2C, SPI | None public (Android Things was retired in 2022) | Vendor SDK on industrial boards | Out of scope |
| CAN bus | No platform API | USB-CAN adapters that speak SLCAN over CDC-ACM work as a serial link | Shipped as raw serial; SLCAN framing planned |
| Audio jack (FSK, "Square" readers) | `AudioRecord`/`AudioTrack` | Modem in software; jack missing on most phones | Out of scope |

### Notes

* **Why BlueLib ships its own USB serial drivers.** The well-known `usb-serial-for-android` library is
  published on JitPack only. A Maven Central library that depends on it would force every consumer to add
  the JitPack repository, so BlueLib implements the four chip families that cover nearly all cables
  (ported from the Linux `cdc-acm`, `ftdi_sio`, `cp210x` and `ch341` drivers) and verifies the register
  values in JVM tests.
* **RJ45 means two things.** Ethernet (TCP/IP) or RS-232/RS-485 wired on an RJ45 connector. See the
  [TCP/IP, USB and serial guide](../guides/wired-and-network.md#serial-over-rj45).
* **Native UART access** is a property of the device image, not of the app. Typical paths: `/dev/ttyS0..9`
  (Rockchip, Allwinner), `/dev/ttyMSM*` (Qualcomm), `/dev/ttyHS*`, `/dev/ttyAMA*` (Raspberry Pi).

## 2. IP networking (Wi-Fi, Ethernet, cellular)

| Link | Android API | Requirements | Status |
| --- | --- | --- | --- |
| TCP client | `java.net.Socket` | `INTERNET` permission | **Shipped** |
| TCP server (device connects to the phone) | `java.net.ServerSocket` | `INTERNET`; phone must be reachable | Planned |
| UDP unicast / broadcast / multicast | `DatagramSocket`, `MulticastSocket` | `INTERNET`; multicast needs `WifiManager.MulticastLock` | Planned |
| Service discovery (mDNS/DNS-SD, Bonjour) | `NsdManager` | `INTERNET` | Planned |
| SSDP/UPnP discovery | UDP multicast | MulticastLock | Planned |
| Join a device's own access point (ESP32 provisioning, IP cameras) | `WifiNetworkSpecifier` (API 29+) | User approval dialog; app-scoped network | Planned |
| Wi-Fi Direct (P2P) | `WifiP2pManager` | `NEARBY_WIFI_DEVICES` (API 33+) or location (older) | Planned |
| Wi-Fi Aware (NAN) | `WifiAwareManager` (API 26+) | Hardware support, `NEARBY_WIFI_DEVICES` | Planned |
| Local-only hotspot | `WifiManager.startLocalOnlyHotspot` | Location/nearby permission | Out of scope |
| WebSocket, HTTP/REST, MQTT, CoAP | OkHttp, Paho, Californium | Application protocols on top of TCP/UDP | Out of scope (use a dedicated client) |
| Google Nearby Connections | Play services | Proprietary, Play services only | Out of scope |

### Application protocols that ride on a byte stream

These work today with `connectTcp`, `connectUsbSerial` or `openUart` plus `sendBytes`, and are candidates
for dedicated framers:

| Protocol | Transport | Framing needed |
| --- | --- | --- |
| ESC/POS (receipt printers) | TCP 9100, USB, Bluetooth SPP | None (command stream) |
| ZPL / TSPL (label printers) | TCP 9100, USB | None |
| Modbus RTU | RS-485 / RS-232 | CRC-16 + inter-frame silence; planned `ModbusRtuFramer` |
| Modbus TCP | TCP 502 | MBAP header (length prefixed, 6 byte header) |
| NMEA 0183 (GPS, marine) | Serial 4800/9600 | Line based: `DelimiterFramer.crlf()` |
| AT commands (modems, HC-05, ESP-AT) | Serial | Line based: `DelimiterFramer.crlf()` |
| SLIP / COBS (embedded links) | Serial | Byte stuffing; planned framers |
| RFC 2217 (Telnet COM port control) | TCP | Lets the app set baud rate on a remote serial port; planned |

## 3. Short-range wireless

| Link | Android API | Requirements | Status |
| --- | --- | --- | --- |
| Bluetooth LE (GATT client/server, advertising, scanning) | `android.bluetooth.le` | See the Bluetooth guides | **Shipped** |
| Bluetooth Classic (RFCOMM/SPP, L2CAP) | `BluetoothSocket` | `BLUETOOTH_CONNECT` | **Shipped** |
| NFC reader (NDEF, ISO-DEP, MIFARE, NFC-A/B/F/V) | `NfcAdapter` reader mode | NFC hardware, foreground activity | Planned (request/response, not a stream) |
| NFC host card emulation | `HostApduService` | Declared service | Planned |
| UWB ranging | `androidx.core.uwb` | UWB hardware, out-of-band parameter exchange | Out of scope (ranging, not data) |
| Bluetooth Channel Sounding | `android.ranging` (API 36+) | See the roadmap | Planned (ranging module) |
| Thread / Matter | Google Home APIs, Thread network APIs | Play services, border router | Out of scope |
| Zigbee, Z-Wave, LoRa, Sub-GHz | No platform API | USB or serial dongle (e.g. CC2652 with ZNP over serial, LoRa modules with AT commands) | Shipped as serial link |
| Infrared | `ConsumerIrManager` | IR blaster, transmit only | Out of scope |

## 4. Recommended next steps

Ordered by value for the device types BlueLib users connect to most (printers, scales, PLCs, IoT modules):

1. **UDP and TCP server** — same `ByteConnection` model; unlocks devices that push data and broadcast
   discovery.
2. **`NsdManager` discovery** — find printers and ESP32 devices by name instead of IP address.
3. **Modbus framers (RTU with CRC-16, TCP with MBAP)** — the most common industrial protocol.
4. **PL2303 driver and multi-port selection** (FT2232/FT4232, CP2105/CP2108).
5. **`WifiNetworkSpecifier` helper** — device provisioning over the device's own access point.
6. **USB attach/detach events as a `Flow`** — today apps poll `usbDevices()`.
7. **NFC** as a separate request/response port, since it does not fit the stream model.
