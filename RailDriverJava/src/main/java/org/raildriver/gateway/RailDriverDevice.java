package org.raildriver.gateway;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.hid4java.*;

/** HID ownership and explicit API normalization; contains no control encoding. */
public final class RailDriverDevice implements AutoCloseable {
    private final HidServices services;
    private final HidDevice device;
    interface Io extends AutoCloseable {
        int read(byte[] buffer, int timeoutMillis);
        boolean attached();
        int write(byte[] payload, byte reportId);
        String error();
        void close();
    }
    private final Io io;
    private final BoundedDiagnostics diagnostics;
    RailDriverDevice(Io io) { this(io, new BoundedDiagnostics(System.out::println)); }
    RailDriverDevice(Io io, BoundedDiagnostics diagnostics) {
        services = null; device = null; this.io = io; this.diagnostics = diagnostics;
    }
    private byte[] previous;
    private long nextPresenceCheck = System.nanoTime();
    private static final int[] DIGITS = {63, 6, 91, 79, 102, 109, 125, 7, 127, 103};

    public RailDriverDevice() throws IOException {
        HidServicesSpecification spec = new HidServicesSpecification();
        spec.setAutoDataRead(false);
        spec.setAutoStart(false);
        spec.setAutoShutdown(false);
        services = HidManager.getHidServices(spec);
        diagnostics = new BoundedDiagnostics(System.out::println);
        try {
            List<HidDevice> matches = services.getAttachedHidDevices().stream()
                    .filter(d -> d.getVendorId() == 0x05f3 && d.getProductId() == 0x00d2
                            && d.getUsagePage() == 12).toList();
            if (matches.size() != 1) throw new IOException("Expected one RailDriver VID=05F3 PID=00D2 usagePage=12; found "
                    + matches.size() + ". Connect one controller, stop VB.NET/other gateways and check HID permissions.");
            device = matches.get(0);
            System.out.printf("RailDriver VID=%04X PID=%04X usagePage=%d path=%s%n",
                    device.getVendorId(), device.getProductId(), device.getUsagePage(), device.getPath());
            if (!device.open()) throw new IOException("Cannot open RailDriver; stop other gateways and check permissions: "
                    + device.getLastErrorMessage());
            io = new Io() {
                public int read(byte[] buffer, int timeout) { return device.read(buffer, timeout); }
                public boolean attached() {
                    return services.getAttachedHidDevices().stream().anyMatch(d -> d.getPath().equals(device.getPath()));
                }
                public int write(byte[] payload, byte reportId) { return device.write(payload, payload.length, reportId); }
                public String error() { return device.getLastErrorMessage(); }
                public void close() { try { device.close(); } finally { services.shutdown(); } }
            };
        } catch (IOException | RuntimeException | LinkageError e) {
            diagnostics.close();
            services.shutdown();
            throw e;
        }
    }

    public byte[] read() throws IOException {
        byte[] raw = new byte[64];
        int length = io.read(raw, 100);
        if (length < 0) throw new IOException("RailDriver USB read failed: " + io.error());
        long now = System.nanoTime();
        if (now - nextPresenceCheck >= 0) {
            nextPresenceCheck = now + 1_000_000_000L;
            boolean attached = io.attached();
            if (!attached) throw new IOException("RailDriver detached; stopping control transmission");
        }
        if (length == 0) return null;
        byte[] observed = Arrays.copyOf(raw, length);
        if (!Arrays.equals(observed, previous)) {
            diagnostics.report("HID input length=" + length + " bytes=" + Arrays.toString(unsigned(observed)));
            previous = observed;
        }
        if (length != 14) {
            throw new IOException("Invalid HID input length=" + length
                    + "; expected 14 unprefixed bytes; confirm controller report layout before restarting");
        }
        return normalize(observed);
    }

    /** Assumption pending device proof: hidapi's 14 bytes omit PIE's leading zero. */
    static byte[] normalize(byte[] raw) {
        if (raw.length != 14) throw new IllegalArgumentException("Expected 14-byte HID input");
        byte[] legacy = new byte[15];
        System.arraycopy(raw, 0, legacy, 1, raw.length);
        return legacy;
    }

    private static int[] unsigned(byte[] bytes) {
        int[] values = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) values[i] = Byte.toUnsignedInt(bytes[i]);
        return values;
    }

    static byte[] logo() { return new byte[] {124, 92, 62, 0, 0, 0, 0}; }
    static byte[] number(int number) {
        if (number < 0) throw new IllegalArgumentException("Negative LED number");
        int hundreds = number / 100;
        int tens = (number - hundreds * 100) / 10;
        return new byte[] {(byte) digit(number % 10), (byte) (hundreds == 0 && tens == 0 ? 0 : digit(tens)),
                (byte) (hundreds == 0 ? 0 : digit(hundreds)), 0, 0, 0, 0};
    }
    private static int digit(int digit) { return digit >= 0 && digit <= 9 ? DIGITS[digit] : 0; }

    public void showLogo() { write(logo()); }
    public void showNumber(int number) { write(number(number)); }
    private void write(byte[] payload) {
        // hid4java prepends the report ID; do not copy PIE's prefixed buffer here.
        int written = io.write(payload, (byte) 134);
        if (written != payload.length + 1) {
            diagnostics.report("LED write failed/short (one attempt): " + written + "; " + io.error());
        }
    }
    @Override public void close() {
        diagnostics.close();
        io.close();
    }
}
