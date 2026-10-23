package org.raildriver.gateway;

import java.util.concurrent.atomic.AtomicInteger;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        int result = run(args);
        if (!(args.length == 1 && args[0].equals("--gui") && result == 0)) System.exit(result);
    }

    static void pump(UdpGateway udp, RailDriverDevice device, AtomicInteger pendingNumber) throws Exception {
        try {
            while (udp.isRunning()) {
                byte[] report = device.read();
                if (report != null) udp.publish(InputMapper.map(report));
                int number = pendingNumber.getAndSet(-1);
                if (number >= 0 && udp.isRunning()) device.showNumber(number);
            }
        } catch (Exception | LinkageError failure) {
            // Stop UDP before potentially slow native resource cleanup.
            udp.close();
            throw failure;
        }
    }

    static int run(String[] args) {
        if (args.length == 1 && args[0].equals("--gui")) {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                System.err.println("RailDriver GUI requires a desktop display; use console mode without --gui.");
                return 2;
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) { /* Swing's default remains available. */ }
                new GatewayWindow().setVisible(true);
            });
            return 0;
        }
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("RailDriver Java gateway (Java 17+)\nUsage: java -jar raildriver-gateway.jar [--help | --gui]\n"
                    + "One instance only; stop VB.NET first. UDP output 127.0.0.1:59983; commands local 59982 from 127.0.0.1:59981.\n"
                    + "HID report layout and LED output require real-device acceptance. Ctrl+C or command 81 stops.");
            return 0;
        }
        if (args.length != 0) { System.err.println("Unknown arguments; use --help"); return 2; }
        AtomicInteger pendingNumber = new AtomicInteger(-1);
        try (UdpGateway udp = new UdpGateway(pendingNumber::set)) {
            Thread owner = Thread.currentThread();
            Thread hook = new Thread(() -> {
                udp.close();
                try { owner.join(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "gateway-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try (RailDriverDevice device = new RailDriverDevice()) {
                device.showLogo();
                pump(udp, device, pendingNumber);
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
