package org.raildriver.gateway;

/** Pure encoder using the legacy PIE API's prefixed report layout. */
public final class InputMapper {
    private InputMapper() { }

    public static byte[] map(byte[] report) {
        if (report == null || report.length < 8) {
            throw new IllegalArgumentException("Legacy input needs at least 8 bytes");
        }
        byte[] frame = new byte[20];
        frame[1] = legacyByte(10 - (int) Math.rint(u(report[3]) / 25.6));
        frame[3] = legacyByte(10 - (int) Math.rint(u(report[4]) / 25.6));
        frame[5] = legacyByte(10 - (int) Math.rint((u(report[2]) - 38) / 18.9));
        frame[16] = (byte) ((u(report[1]) < 127 ? 8 : 4) | (u(report[7]) > 150 ? 1 : 0));
        int brake = u(report[6]);
        frame[18] = (byte) (brake > 150 ? 128 : brake > 110 && brake < 150 ? 64 : 0);
        frame[19] = (byte) (8 | (u(report[3]) < 70 ? 64 : 0));
        return frame;
    }

    private static int u(byte value) { return Byte.toUnsignedInt(value); }
    private static byte legacyByte(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Legacy mapping is outside Byte range: " + value
                    + "; verify controller calibration/report offsets");
        }
        return (byte) value;
    }
}
