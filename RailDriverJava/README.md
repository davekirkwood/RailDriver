# Java RailDriver gateway

Independent Java 17+ console alternative to the retained VB.NET gateway. It preserves the simulator's 20-byte binary UDP interface. Initial macOS hardware checks (real HID/UDP, LED 107 and unplug exit) passed; complete device, simulator and Windows release acceptance is **pending**; see [VERIFICATION.md](VERIFICATION.md). Stop VB.NET before starting Java. Run one gateway only.

## Build and run

Install a full JDK 17+ and Maven, then from the repository root:

```sh
mvn -f RailDriverJava/pom.xml clean verify
java -jar RailDriverJava/target/raildriver-gateway.jar --help
java -jar RailDriverJava/target/raildriver-gateway.jar
```

On JDK 24+, use `java --enable-native-access=ALL-UNNAMED -jar RailDriverJava/target/raildriver-gateway.jar` to permit native loading without the JNA restricted-access warning. No PIE DLL installation, JMRI runtime or separate dependency classpath is required. The shaded JAR retains hid4java and JNA native resources. Native access/USB permissions still depend on the host. Connect a PI Engineering RailDriver (expected VID `05F3`, PID `00D2`, usage page `12`). Missing, ambiguous or inaccessible controllers stop with a nonzero exit and diagnostic. Startup diagnostics show identity. Ongoing changed-report diagnostics include observed length and unsigned bytes on a best-effort basis: a daemon worker and single-slot queue keep console output off the input pump, and messages may be dropped if the console is blocked. Ongoing LED-write diagnostics use the same bounded queue; shutdown does not wait for console writes.

No control frames are sent before the first complete valid report. USB negative read or detachment stops transmission; timeout alone means no new input. Detach enumeration is checked at most once per second, so a driver that reports only timeouts may leave up to about one second of previously valid output before detachment is detected. There is no reconnect loop. Ctrl+C or the legacy shutdown command requests shutdown and closes the UDP sockets. The JVM shutdown hook waits up to two seconds for the owner thread to finish cleanup; blocked native calls may prevent explicit HID close, and the OS reclaims remaining handles when the process terminates. A bind conflict on command port fails startup. LED output makes one write attempt and reports failure; hid4java has no write-timeout parameter, so the native driver determines the duration of that call.

## Ports and packet contract

| Purpose | Local / peer |
| --- | --- |
| Output | ephemeral local UDP port → `127.0.0.1:59983`, nominal 10 ms between sends |
| Commands | bound `127.0.0.1:59982`, connected to peer `127.0.0.1:59981` |

Only datagrams from that command peer are accepted. Commands must contain exactly three bytes: `[81, any, any]` shuts down; `[83, b1, b2]` displays unsigned `b1*100+b2`. Unknown/short/long commands are ignored. Values above 999 keep the VB behaviour: an out-of-table hundreds digit is blank. If the simulator starts later or its UDP listener is temporarily absent, `PortUnreachableException` is diagnosed once and transmission attempts continue at the nominal 10 ms cadence; other transport I/O failures stop the gateway. Simulator connection-loss policy belongs to the simulator.

All packet indices are zero-based. Bytes 1/3/5 hold LJ/MLJ/throttle. Byte 16 is direction (`4` or `8`) plus key bit `1`; byte 17 is zero; byte 18 holds LB3/LB4 bits `64`/`128`; byte 19 always includes `8` plus RCC bit `64`. All other bytes are zero. Physical button bytes are not forwarded, following VB. Brake arithmetic uses `10-roundEven(raw/25.6)`; throttle uses `10-roundEven((raw-38)/18.9)`. Values outside VB Byte range fail clearly instead of silently clamping. Historical calibration can produce values above 10; this is preserved when representable.

## HID compatibility assumptions

The pure mapper accepts PIE's legacy prefixed layout. The device adapter currently accepts exactly 14 hidapi input bytes and prepends one zero byte: raw throttle index 1 becomes legacy index 2. A real macOS session confirmed 14-byte reports and throttle movement at raw index 1, with consistent UDP output. Full control-by-control mapping and equality with a VB.NET capture remain unverified; see the recorded hardware evidence. Unexpected report lengths stop the gateway with an actionable error, without publishing a fabricated frame or continuing to transmit the previous state indefinitely. Confirm actual controller report numbering and offsets against VB before release; a different layout requires a reviewed normalization change.

For output, hid4java `write(payload, length, reportId)` prepends the ID. We supply report ID `134` and seven payload bytes, beginning with the rightmost, middle and leftmost LED segments. The startup logo, leading blanking and unusual VB digit-9 segment value `103` are preserved. Do not pass the old PIE output buffer unchanged. A real macOS session displayed 107 correctly, confirmed by the user. Other numbers/logo and Windows operation remain hardware gates.

## Windows app image

On **native Windows**, set `JAVA_HOME` to a full JDK 17+ of the target architecture and put Maven on PATH:

```powershell
.\RailDriverJava\package-windows.ps1
.\RailDriverJava\dist\RailDriverGateway\RailDriverGateway.exe --help
.\RailDriverJava\dist\RailDriverGateway\RailDriverGateway.exe
```

`jpackage` creates a console launcher and bundled runtime. Copy the **whole** `RailDriverGateway` directory to the target machine. No installer, Docker, WSL or separate target JRE is required. Packaging must run on Windows; it has not been run here. The destination app-image folder must not already exist; choose `-Destination` or remove an old generated image deliberately. Run the clean-machine checklist before distributing.

## Fallback and rights

Stop Java before launching the original `BRaVE_RailDriver.sln` application. The original VB project and solution are unchanged. No licence is granted to the existing repository or this port by these changes: owner permission and review remain release gates.

Dependencies: hid4java **0.8.0** (MIT), JNA **5.19.1** (dual Apache-2.0/LGPL-2.1-or-later), and bundled hidapi native libraries (upstream offers BSD/GPLv3/original HIDAPI licence choices). JNA is pinned explicitly because hid4java permits a version range. Copies of hid4java, JNA and hidapi BSD terms are retained under `src/main/resources/META-INF/raildriver-notices/` and inside the executable JAR; review exact bundled-native provenance and redistribution obligations before release. The project's JUnit dependency is test-only. No JMRI code was copied and JMRI is not a dependency.

API reference: [hid4java 0.8.0 HidDevice](https://github.com/gary-rowe/hid4java/blob/hid4java-0.8.0/src/main/java/org/hid4java/HidDevice.java), [hid4java licensing](https://github.com/gary-rowe/hid4java/blob/hid4java-0.8.0/LICENSE), [hidapi licensing](https://github.com/libusb/hidapi/blob/hidapi-0.14.0/LICENSE.txt).
