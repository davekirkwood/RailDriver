package org.raildriver.gateway;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.function.Consumer;

/** Original Java2D geometry, inspired by controller proportions; read-only hardware view. */
public final class DevicePanel extends JPanel {
    private GatewaySession.Sample sample;
    private byte[] previous;
    private final Map<Integer, Long> changed = new HashMap<>();
    private final Map<Shape, Source> hits = new LinkedHashMap<>();
    private Source selected;
    private Consumer<String> selection = ignored -> { };
    private static final String[] NAMES = {"Direction", "Throttle", "Brake A / LJ", "Brake B / MLJ", "Bail-off", "Selector A", "Selector B"};
    public DevicePanel() {
        setPreferredSize(new Dimension(850, 430)); setMinimumSize(new Dimension(350, 240));
        setBackground(new Color(242, 244, 247)); setToolTipText("Read-only physical inputs; no report observed");
        setFocusable(true);
        getAccessibleContext().setAccessibleName("Read-only RailDriver device. Values and bits also available in Packets tab.");
        addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) {
            requestFocusInWindow(); selected = sourceAt(e.getPoint()); selection.accept(detailAt(e.getPoint()));
        }});
    }
    public void onSelection(Consumer<String> listener) { selection = listener; }
    public void setSample(GatewaySession.Sample next) {
        if (next == sample) { repaint(); return; }
        byte[] raw = next == null ? null : next.raw();
        if (raw != null && previous != null) for (int i = 0; i < raw.length; i++) {
            int delta = (raw[i] ^ previous[i]) & 255;
            if (delta != 0) changed.put(i, System.nanoTime());
            if (i >= 7) for (int bit = 0; bit < 8; bit++)
                if ((delta & (1 << bit)) != 0) changed.put(100 + i * 8 + bit, System.nanoTime());
        }
        if (next == null) changed.clear();
        previous = raw; sample = next;
        if (selected != null) selection.accept(describe(selected));
        repaint();
    }
    @Override public String getToolTipText(MouseEvent e) { return detailAt(e.getPoint()); }
    private record Source(int index, int bit) { }
    private String describe(Source source) {
        byte[] raw = sample == null ? null : sample.raw();
        int i = source.index();
        if (source.bit() >= 0) return "Observed raw[" + i + "] bit " + source.bit() + ": "
                + (raw == null ? "unavailable" : (raw[i] & (1 << source.bit())) != 0 ? "1" : "0")
                + "; graphical key position unverified; general buttons are not forwarded by legacy encoder";
        return NAMES[i] + ": raw[" + i + "] → normalized legacy[" + (i + 1) + "]; "
                + (raw == null ? "unavailable — no observation" : "value " + (raw[i] & 255))
                + (i == 1 ? "; throttle device-confirmed" : "; physical mapping unverified");
    }
    private String detailAt(Point p) {
        Source source = sourceAt(p);
        return source == null ? "Read-only hardware view. Throttle raw 1 confirmed; other physical mappings and key placement unverified." : describe(source);
    }
    private Source sourceAt(Point p) {
        double scale = Math.min(getWidth() / 900.0, getHeight() / 450.0);
        if (scale <= 0) return null;
        Point2D point = new Point2D.Double((p.x - (getWidth() - 900 * scale) / 2) / scale,
                (p.y - (getHeight() - 450 * scale) / 2) / scale);
        for (var hit : hits.entrySet()) if (hit.getKey().contains(point)) return hit.getValue();
        return null;
    }
    private boolean recent(int index) {
        Long time = changed.get(index); return time != null && System.nanoTime() - time < 700_000_000L;
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            double scale = Math.min(getWidth() / 900.0, getHeight() / 450.0);
            g.translate((getWidth() - 900 * scale) / 2, (getHeight() - 450 * scale) / 2); g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            hits.clear();
            g.setColor(new Color(52, 63, 76)); g.fill(new RoundRectangle2D.Double(18, 16, 864, 408, 48, 48));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17)); g.setColor(Color.WHITE);
            g.drawString("RAILDRIVER  /  PHYSICAL INPUTS", 44, 46);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(204, 216, 231));
            g.drawString(sample == null ? "NO REPORT OBSERVED — values unavailable" : "Observed HID • graphical controls are read-only", 44, 68);
            byte[] raw = sample == null ? null : sample.raw();
            for (int i = 0; i < 4; i++) {
                int x = 55 + i * 140, y = 113;
                Shape area = new RoundRectangle2D.Double(x, 86, 124, 205, 14, 14);
                g.setColor(new Color(71, 84, 100)); g.fill(area);
                g.setColor(recent(i) ? new Color(102, 216, 255) : new Color(169, 184, 203)); g.draw(area);
                g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13)); g.drawString(NAMES[i], x + 9, y - 6);
                g.setColor(new Color(27, 35, 46)); g.fillRoundRect(x + 51, y + 8, 22, 109, 10, 10);
                if (raw != null) {
                    int lever = y + 9 + ((raw[i] & 255) * 86 / 255);
                    g.setColor(recent(i) ? new Color(102, 216, 255) : new Color(226, 231, 237));
                    g.fillRoundRect(x + 18, lever, 88, 25, 9, 9);
                    g.setColor(new Color(28, 40, 54)); g.drawLine(x + 32, lever + 12, x + 91, lever + 12);
                } else { g.setColor(new Color(219, 229, 241)); g.drawString("unavailable", x + 26, y + 70); }
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); g.setColor(Color.WHITE);
                g.drawString("raw " + i + ": " + (raw == null ? "—" : raw[i] & 255), x + 9, 258);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.drawString(i == 1 ? "device-confirmed" : "mapping unverified", x + 9, 277);
                hits.put(area, new Source(i, -1));
            }
            for (int i = 5; i <= 6; i++) {
                int x = 678, y = i == 5 ? 118 : 217;
                Shape area = new Ellipse2D.Double(x, y - 25, 68, 68);
                g.setColor(new Color(25, 34, 45)); g.fill(area);
                g.setColor(recent(i) ? Color.CYAN : new Color(180, 194, 212)); g.draw(area);
                if (raw != null) {
                    double angle = Math.PI * (0.75 + 1.5 * (raw[i] & 255) / 255.0);
                    g.setStroke(new BasicStroke(5)); g.drawLine(x + 34, y + 9,
                            x + 34 + (int) (25 * Math.cos(angle)), y + 9 + (int) (25 * Math.sin(angle))); g.setStroke(new BasicStroke(1));
                }
                g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.drawString("raw " + i + "  " + (raw == null ? "—" : raw[i] & 255), x + 78, y + 11);
                hits.put(area, new Source(i, -1));
            }
            g.setColor(Color.WHITE); g.drawString("Bail-off raw 4: " + (raw == null ? "—" : raw[4] & 255), 655, 284);
            g.setColor(new Color(193, 210, 231)); g.drawString("Observed bits • key positions unverified", 47, 317);
            for (int bit = 0; bit < 28; bit++) {
                int index = 7 + bit / 8, mask = 1 << (bit % 8), x = 46 + (bit % 14) * 58, y = 329 + (bit / 14) * 39;
                boolean active = raw != null && (raw[index] & mask) != 0;
                Shape key = new RoundRectangle2D.Double(x, y, 51, 32, 7, 7);
                g.setColor(raw == null ? new Color(55, 68, 82) : active ? new Color(51, 151, 224) : new Color(36, 76, 116)); g.fill(key);
                g.setColor(recent(100 + index * 8 + bit % 8) ? Color.CYAN : active ? Color.WHITE : new Color(128, 164, 202));
                g.setStroke(new BasicStroke(recent(100 + index * 8 + bit % 8) ? 3 : 1)); g.draw(key); g.setStroke(new BasicStroke(1));
                g.setColor(active ? new Color(12, 26, 40) : new Color(222, 235, 250));
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11)); g.drawString(index + "." + bit % 8 + (raw == null ? "?" : ""), x + 7, y + 20);
                hits.put(key, new Source(index, bit % 8));
            }
        } finally { g.dispose(); }
    }
}
