package org.raildriver.gateway;

import java.util.concurrent.atomic.AtomicInteger;

public final class Main {
    private Main() { }
    public static void main(String[] args) { System.exit(run(args)); }

    static int run(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("RailDriver Java gateway (Java 17+)\nUsage: java -jar raildriver-gateway.jar [--help]\n"
                    + "One instance only; stop VB.NET first. UDP output 127.0.0.1:59983; commands local 59982 from 127.0.0.1:59981.\n"
                    + "HID report layout and LED output require real-device acceptance. Ctrl+C or command 81 stops.");
            return 0;
        }
        if (args.length != 0) { System.err.println("Unknown arguments; use --help"); return 2; }
        AtomicInteger pendingNumber = new AtomicInteger(-1);
        try (UdpGateway udp = new UdpGateway(pendingNumber::set)) {
            Thread hook = new Thread(udp::close, "gateway-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try (RailDriverDevice device = new RailDriverDevice()) {
                device.showLogo();
                while (udp.isRunning()) {
                    try {
                        byte[] report = device.read();
                        if (report != null) udp.publish(InputMapper.map(report));
                    } catch (Exception | LinkageError failure) {
                        // Stop UDP before potentially slow native resource cleanup.
                        udp.close();
                        throw failure;
                    }
                    int number = pendingNumber.getAndSet(-1);
                    if (number >= 0 && udp.isRunning()) device.showNumber(number);
                }
                if (udp.failure() != null) throw udp.failure();
            } finally {
                udp.close();
                udp.awaitStopped();
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException shuttingDown) { /* VM shutdown already underway. */ }
            }
            return 0;
        } catch (Exception | LinkageError e) {
            System.err.println("RailDriver gateway stopped: " + e.getMessage());
            return 1;
        }
    }
}
