package org.raildriver.gateway;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Local-only settings. Construct/validate before opening any resource. */
public record GatewayConfig(int commandPort, int peerPort, int destinationPort,
                            int sendDelayMillis, boolean diagnostics) {
    public static GatewayConfig defaults() { return new GatewayConfig(59982, 59981, 59983, 10, true); }
    public GatewayConfig {
        for (int port : new int[]{commandPort, peerPort, destinationPort})
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Ports must be between 1 and 65535");
        if (commandPort == peerPort || commandPort == destinationPort || peerPort == destinationPort)
            throw new IllegalArgumentException("Use three different loopback ports");
        if (sendDelayMillis < 1 || sendDelayMillis > 1000)
            throw new IllegalArgumentException("Send delay must be between 1 and 1000 ms");
    }
    public void save(Path path) throws IOException {
        Properties p = new Properties();
        p.setProperty("commandPort", "" + commandPort); p.setProperty("peerPort", "" + peerPort);
        p.setProperty("destinationPort", "" + destinationPort); p.setProperty("sendDelayMillis", "" + sendDelayMillis);
        p.setProperty("diagnostics", "" + diagnostics);
        AtomicFiles.replace(path, writer -> p.store(writer, "RailDriver local loopback settings"));
    }
    public static GatewayConfig load(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        try {
            String diagnostic = required(p, "diagnostics");
            if (!diagnostic.equals("true") && !diagnostic.equals("false"))
                throw new IllegalArgumentException("diagnostics must be true or false");
            return new GatewayConfig(Integer.parseInt(required(p, "commandPort")),
                    Integer.parseInt(required(p, "peerPort")), Integer.parseInt(required(p, "destinationPort")),
                    Integer.parseInt(required(p, "sendDelayMillis")), Boolean.parseBoolean(diagnostic));
        } catch (IllegalArgumentException e) { throw new IOException("Invalid settings: " + e.getMessage(), e); }
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value.trim();
    }
}
