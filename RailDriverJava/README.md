# Java RailDriver gateway

A Java 17+ alternative to the original VB.NET gateway, with console operation and a native Swing workbench. It reads a physical RailDriver controller and sends the existing simulator's 20-byte UDP frames. The original VB.NET project is retained unchanged.

![Swing device workbench showing lever channels, observed input bits, connection status and LED controls](docs/images/swing-device-preview.png)

*Preview: actual Swing components painted with synthetic input. This is a software render, not a live-controller screenshot. The illustration is original Java2D geometry; physical mappings marked unverified still require confirmation.*

## Quick start

You need a full **JDK 17+**, **Maven**, and a PI Engineering RailDriver controller. Run these commands from the repository root:

```sh
mvn -f RailDriverJava/pom.xml clean verify
java -jar RailDriverJava/target/raildriver-gateway.jar --gui
```

Connect the controller, select **Start**, then move a physical control to inspect its input. The window opens stopped and shows unavailable values until it receives a complete report. Run only one gateway at a time: stop VB.NET before starting Java, and stop Java before returning to VB.NET.

For console mode or command help:

```sh
java -jar RailDriverJava/target/raildriver-gateway.jar
java -jar RailDriverJava/target/raildriver-gateway.jar --help
```

On JDK 24+, add the native-access option before `-jar` (append `--gui` for the workbench):

```sh
java --enable-native-access=ALL-UNNAMED -jar RailDriverJava/target/raildriver-gateway.jar --gui
```

The executable JAR includes hid4java, JNA and their native resources. A separate PIE DLL installation, JMRI runtime or dependency classpath is not required. USB access still depends on host permissions. The expected controller identity is VID `05F3`, PID `00D2`, usage page `12`.

## Using the workbench

| View | Purpose |
| --- | --- |
| **Device** | Read-only controller drawing with lever channels, selectors, bail-off and observed input bits. Changed inputs are highlighted; click or hover for byte details. Test the physical LED with a number from 0–999. |
| **Packets** | Inspect a coherent 14-byte raw report and encoded 20-byte frame, timestamps, raw bits and legacy flag meanings. This also provides textual access to the graphical input values. |
| **Settings** | Change the three distinct loopback ports, nominal send delay and HID diagnostics while stopped. Explicitly load/save local Java properties files. |
| **Events** | Inspect lifecycle, device and LED-write diagnostics. Use **Export capture** to save captured reports, packets and run summaries as CSV. |

**Start / Stop.** Missing devices and port conflicts produce recoverable errors. Reconnect or correct the settings, then select Start again. Stop closes UDP independently of native HID cleanup; restart stays disabled until cleanup finishes. Closing the window requests shutdown and waits for pending file operations. Remote command 81 stops the session and leaves the window open. There is no automatic reconnect loop.

**Input interpretation.** Throttle raw index 1 has physical confirmation. Brake A/LJ raw 2, Brake B/MLJ raw 3, direction/selectors, bail-off naming and graphical key placement still require physical verification. Key labels identify observed byte/bit positions rather than simulator actions. The graphic cannot simulate input or calibrate the controller; general buttons remain omitted from the legacy packet format.

**LED tests and telemetry.** A successful LED write means the driver accepted it; confirm the displayed number on the actual device. Only the newest queued GUI LED request is retained. Send counts and average rate measure local UDP send successes, not simulator receipt. Report age is shown separately: a read timeout may mean the input has not changed.

**Settings and capture.** Ports accept 1–65535 and nominal send delay accepts 1–1000 ms. Settings are locked during active sessions and file operations; invalid loaded settings preserve current fields. The window retains up to 5,000 report/packet pairs, 200 events and 200 completed run summaries plus the active run. Eviction and diagnostic-drop counts are reported. CSV includes UTC/monotonic timestamps, run IDs, raw/encoded hex, provenance and send/drop summaries. Captured reports are not a record of every repeated UDP send. Closing the window discards in-memory capture; export it first. Diagnostic events may include controller identity/path. Save/export use a temporary sibling file before replacing the destination.

USB, network and file operations run off Swing's event thread. A 100 ms UI timer reads the latest immutable snapshot. Native LED writes and cleanup may still take driver-defined time; the window remains responsive during cleanup.

## Connection and protocol

Defaults preserve the existing simulator interface:

| Purpose | Address / timing |
| --- | --- |
| Control output | Ephemeral local UDP port → `127.0.0.1:59983` |
| Command listener | `127.0.0.1:59982`, accepting only peer `127.0.0.1:59981` |
| Send delay | Nominal 10 ms between transmissions; not a hard real-time guarantee |

Commands must be exactly three bytes:

- `[81, any, any]`: request shutdown.
- `[83, b1, b2]`: display unsigned `b1 * 100 + b2`. Values above 999 preserve VB behaviour: an out-of-table hundreds digit is blank.

Wrong-source, malformed and unknown commands are ignored. If the simulator starts later or temporarily stops listening, `PortUnreachableException` is diagnosed once and transmission attempts continue. Other transport I/O failures stop the gateway.

No control frames are sent before the first complete valid report. An unexpected report length, negative USB read or detected detachment stops transmission. Timeout alone is not a disconnect. Detach enumeration occurs at most once per second; a driver returning only timeouts can therefore leave approximately one second of previous output before detection. Console Ctrl+C and command shutdown close UDP; the JVM hook waits up to two seconds for owner-thread cleanup. Blocked native calls may prevent explicit HID close before process termination. Diagnostics use a bounded background queue and may be dropped rather than blocking the input pump.

### Legacy packet layout

All indices are zero-based. Unlisted bytes are zero.

| Byte | Contents |
| --- | --- |
| 1 / 3 / 5 | LJ / MLJ / throttle |
| 16 | Direction (`4` or `8`) plus key bit `1` |
| 17 | Zero |
| 18 | LB3 / LB4 bits `64` / `128` |
| 19 | Always `8`, plus RCC bit `64` |

Brake arithmetic is `10 - roundEven(raw / 25.6)`; throttle is `10 - roundEven((raw - 38) / 18.9)`. Values outside VB Byte range fail rather than clamp. Historical calibration may produce representable values above 10; these are preserved.

The HID adapter accepts exactly 14 input bytes and prepends a zero byte for the legacy mapper, so raw channels 0–6 become legacy indices 1–7. Full control mapping and equality against VB.NET captures remain pending. LED output uses report ID `134` with seven payload bytes, ordered rightmost, middle, leftmost segments. The original startup logo, blanking and digit-9 segment value `103` are preserved; only numeric 107 has visual hardware confirmation. Each LED request makes one native write attempt; hid4java exposes no write timeout.

## Windows distribution

On **native Windows**, set `JAVA_HOME` to a full JDK 17+ matching the target architecture and put Maven on PATH:

```powershell
.\RailDriverJava\package-windows.ps1
.\RailDriverJava\dist\RailDriverGateway\RailDriverGateway.exe --help
.\RailDriverJava\dist\RailDriverGateway\RailDriverGateway.exe
.\RailDriverJava\dist\RailDriverGateway\RailDriverWorkbench.exe
```

The script uses `jpackage` to create a bundled runtime, the console launcher `RailDriverGateway.exe`, and the GUI launcher `RailDriverWorkbench.exe`. Copy the **whole** `RailDriverGateway` directory to the target machine; a separately installed JRE is not needed. The destination must not already exist: choose `-Destination` or deliberately remove the old generated image.

Windows packaging and clean-machine execution have not been verified. Before distribution, test the complete app image on a clean Windows machine without Java installed: run both launchers, check missing-device and port-conflict errors, then verify controller input, simulator output, LED writes, unplug and shutdown behaviour.

## Verification status

- **Software:** 32 tests passed on Java 17, including fake-HID/real-loopback integration and headless Swing interactions/rendering.
- **macOS controller:** real 14-byte HID input, 20-byte UDP output, throttle movement, visually confirmed LED 107, unplug shutdown, and two Swing-session API start/stop cycles were checked.
- **Pending:** complete physical mapping, VB.NET equivalence, existing-simulator integration, native GUI click-through and Windows packaging/execution.

Recorded evidence is retained under [verification/](verification/). Software renders do not establish native GUI or hardware acceptance. To use the original fallback, stop Java and open `BRaVE_RailDriver.sln` on Windows.

## Dependencies and licensing

Runtime dependencies are hid4java **0.8.0** (MIT), JNA **5.19.1** (Apache-2.0/LGPL-2.1-or-later), and bundled hidapi native libraries (upstream offers BSD/GPLv3/original HIDAPI licence choices). JNA is pinned explicitly; JUnit is test-only. No JMRI code is included.

Dependency notices are retained in [src/main/resources/META-INF/raildriver-notices/](src/main/resources/META-INF/raildriver-notices/) and inside the JAR. These changes do not grant a licence to the repository or port; owner permission, exact native-library provenance and bundled-JDK redistribution terms remain release checks.

References: [hid4java HidDevice API](https://github.com/gary-rowe/hid4java/blob/hid4java-0.8.0/src/main/java/org/hid4java/HidDevice.java), [hid4java licence](https://github.com/gary-rowe/hid4java/blob/hid4java-0.8.0/LICENSE), [hidapi licensing](https://github.com/libusb/hidapi/blob/hidapi-0.14.0/LICENSE.txt).
