package org.raildriver.gateway;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native diagnostic workbench. File, network and USB work never runs on the EDT. */
final class GatewayContent extends JPanel {
    @FunctionalInterface interface Notices { void show(String title, String message, int kind); }
    final Runnable onClosed;
    final Notices notices;
    final GatewaySession session;
    final JButton start = button("Start", KeyEvent.VK_S), stop = button("Stop", KeyEvent.VK_T);
    final JButton export = button("Export capture…", KeyEvent.VK_E), load = button("Load…", KeyEvent.VK_L), save = button("Save…", KeyEvent.VK_V);
    final JButton led = button("Write LED", KeyEvent.VK_W);
    final JTextArea status = area(false), packets = area(true), events = area(true);
    final JLabel counts = new JLabel("0 successful sends • no samples");
    final DevicePanel device = new DevicePanel();
    final JTextArea detail = area(false);
    final JTextField command = new JTextField("59982", 9), peer = new JTextField("59981", 9), destination = new JTextField("59983", 9), delay = new JTextField("10", 9);
    final JCheckBox diagnostics = new JCheckBox("Capture HID diagnostic events", true);
    final JSpinner number = new JSpinner(new SpinnerNumberModel(107, 0, 999, 1));
    final JLabel fileStatus = new JLabel("Settings apply on Start; defaults retained until explicitly changed.");
    final Timer timer;
    boolean closing, fileBusy, exporting;
    int pendingFiles;
    boolean closed;

    GatewayContent(GatewaySession session, Runnable onClosed, Notices notices) {
        super(new BorderLayout(8, 12)); this.session = session; this.onClosed = onClosed;
        this.notices = notices == null ? (title, message, kind) -> JOptionPane.showMessageDialog(this, message, title, kind) : notices;
        JPanel header = new JPanel(new BorderLayout(8, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT)); actions.add(start); actions.add(stop); actions.add(export);
        header.add(actions, BorderLayout.NORTH); status.setRows(3); header.add(status, BorderLayout.CENTER); header.add(counts, BorderLayout.SOUTH);
        JTabbedPane tabs = new JTabbedPane();
        JPanel deviceTab = new JPanel(new BorderLayout(12, 8)); deviceTab.add(device, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(12, 8));
        detail.setText("Move a physical control to observe it. Click a graphic for its byte source.\nThrottle raw 1 confirmed; brake/selector mappings and graphical key placement unverified.");
        detail.setRows(3); footer.add(detail, BorderLayout.CENTER);
        JPanel ledBox = new JPanel(new FlowLayout(FlowLayout.LEFT)); JLabel ledLabel = new JLabel("LED number:"); ledLabel.setDisplayedMnemonic(KeyEvent.VK_N); ledLabel.setLabelFor(number);
        ledBox.add(ledLabel); ledBox.add(number); ledBox.add(led); footer.add(ledBox, BorderLayout.SOUTH); deviceTab.add(footer, BorderLayout.SOUTH);
        device.onSelection(detail::setText);
        tabs.addTab("Device", deviceTab); tabs.setMnemonicAt(0, KeyEvent.VK_D);
        tabs.addTab("Packets", new JScrollPane(packets)); tabs.setMnemonicAt(1, KeyEvent.VK_P);
        tabs.addTab("Settings", settings()); tabs.setMnemonicAt(2, KeyEvent.VK_G);
        tabs.addTab("Events", new JScrollPane(events)); tabs.setMnemonicAt(3, KeyEvent.VK_R);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(header, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER);
        start.addActionListener(e -> { try { session.start(readConfig()); } catch (IllegalArgumentException failure) { showError("Invalid settings", failure); } refresh(); });
        stop.addActionListener(e -> { session.stop(); refresh(); });
        led.addActionListener(e -> writeLed());
        load.addActionListener(e -> load()); save.addActionListener(e -> save()); export.addActionListener(e -> export());
        timer = new Timer(100, e -> refresh()); timer.start(); refresh();
    }
    private JPanel settings() {
        JPanel fields = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 9, 7, 9); c.anchor = GridBagConstraints.WEST; c.gridy = 0;
        addSetting(fields, c, "Command receive port (127.0.0.1)", KeyEvent.VK_C, command);
        addSetting(fields, c, "Allowed command peer port (127.0.0.1)", KeyEvent.VK_A, peer);
        addSetting(fields, c, "Simulator destination port (127.0.0.1)", KeyEvent.VK_O, destination);
        addSetting(fields, c, "Nominal send delay (1–1000 ms)", KeyEvent.VK_I, delay);
        c.gridx = 0; c.gridwidth = 2; fields.add(diagnostics, c); c.gridy++;
        JPanel files = new JPanel(new FlowLayout(FlowLayout.LEFT)); files.add(load); files.add(save); fields.add(files, c); c.gridy++;
        fields.add(fileStatus, c); c.gridy++;
        JTextArea note = area(false); note.setColumns(48); note.setRows(4);
        note.setText("All endpoints use IPv4 loopback. Use distinct ports from 1 to 65535. Settings are locked until resources finish stopping.\nProperties are loaded/saved only through these buttons. Loading a malformed file preserves current values.");
        fields.add(note, c);
        JPanel panel = new JPanel(new BorderLayout()); panel.add(fields, BorderLayout.NORTH); return panel;
    }
    private static void addSetting(JPanel panel, GridBagConstraints c, String text, int mnemonic, JTextField field) {
        c.gridx = 0; JLabel label = new JLabel(text); label.setDisplayedMnemonic(mnemonic); label.setLabelFor(field); panel.add(label, c);
        c.gridx = 1; panel.add(field, c); c.gridy++;
    }
    GatewayConfig readConfig() {
        try { return new GatewayConfig(Integer.parseInt(command.getText().trim()), Integer.parseInt(peer.getText().trim()),
                Integer.parseInt(destination.getText().trim()), Integer.parseInt(delay.getText().trim()), diagnostics.isSelected()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Ports and send delay must be whole numbers"); }
    }
    void setConfig(GatewayConfig config) {
        command.setText("" + config.commandPort()); peer.setText("" + config.peerPort()); destination.setText("" + config.destinationPort());
        delay.setText("" + config.sendDelayMillis()); diagnostics.setSelected(config.diagnostics());
    }
    private Path choose(boolean saving, String title) {
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle(title);
        if ((saving ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this)) != JFileChooser.APPROVE_OPTION) return null;
        java.io.File file = chooser.getSelectedFile();
        if (saving && file.exists() && JOptionPane.showConfirmDialog(this, "Replace " + file.getName() + "?", "Existing file", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
        return file.toPath();
    }
    private void load() {
        Path path = choose(false, "Load RailDriver properties"); if (path == null) return;
        loadFrom(path);
    }
    private void save() {
        GatewayConfig config;
        try { config = readConfig(); } catch (IllegalArgumentException e) { showError("Invalid settings", e); return; }
        Path path = choose(true, "Save RailDriver properties"); if (path == null) return;
        saveTo(path, config);
    }
    void loadFrom(Path path) {
        fileTask(() -> GatewayConfig.load(path), config -> { setConfig(config); fileStatus.setText("Loaded " + path.getFileName()); });
    }
    void saveTo(Path path, GatewayConfig config) {
        fileTask(() -> { config.save(path); return path; }, saved -> fileStatus.setText("Saved " + saved.getFileName()));
    }
    boolean writeLed() {
        try {
            String typed = ((JSpinner.DefaultEditor) number.getEditor()).getTextField().getText().trim();
            number.commitEdit();
            if (!typed.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Use whole decimal digits only");
            int value = ((Number) number.getValue()).intValue();
            if (value < 0 || value > 999) throw new IllegalArgumentException("LED test must be 0–999");
            boolean queued = session.led(value); refresh(); return queued;
        } catch (java.text.ParseException | IllegalArgumentException failure) {
            showError("Invalid LED number", new IllegalArgumentException("Enter a whole LED number from 0 to 999: " + failure.getMessage())); return false;
        }
    }
    <T> void fileTask(Callable<T> task, Consumer<T> completed) {
        fileBusy = true; pendingFiles++; refresh();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                fileBusy = false; pendingFiles--;
                try { T result = get(); if (!closing) completed.accept(result); }
                catch (Exception e) { if (!closing) showError("File operation failed", e.getCause() == null ? e : e.getCause()); }
                refresh();
            }
        }.execute();
    }
    void requestClose() { closing = true; session.stop(); refresh(); }
    private void export() {
        if (session.view().captured() == 0) { notices.show("Empty capture", "Capture is empty. Start and receive a valid report first.", JOptionPane.INFORMATION_MESSAGE); return; }
        Path path = choose(true, "Export observed reports and legacy packets (CSV)"); if (path == null) return;
        exportTo(path);
    }
    void exportTo(Path path) { trackExport(session.export(path), path); }
    void trackExport(CompletableFuture<Integer> operation, Path path) {
        exporting = true; pendingFiles++; refresh();
        operation.whenComplete((count, failure) -> SwingUtilities.invokeLater(() -> {
            exporting = false; pendingFiles--;
            if (!closing) {
                if (failure != null) showError("Capture export failed", failure.getCause() == null ? failure : failure.getCause());
                else notices.show("Capture exported", "Exported " + count + " observed reports to " + path + "\nContains raw bytes, encoded packets, timestamps and provenance.", JOptionPane.INFORMATION_MESSAGE);
            }
            refresh();
        }));
    }
    void refresh() {
        GatewaySession.View view = session.view();
        GatewaySession.Sample current = view.latest();
        String age = current == null ? "No current input report." : String.format("Last report: %.1f s ago. Timeouts may mean unchanged input.", Math.max(0, System.nanoTime() - current.monotonicNanos()) / 1e9);
        status.setText(view.state() + "  ·  " + view.message() + "\n" + age);
        counts.setText(String.format("%,d successful sends • %.1f sends/s since Start • %,d captured • %,d evicted", view.sent(), view.rate(), view.captured(), view.evicted()));
        boolean editable = !view.active() && !fileBusy && !closing;
        start.setEnabled(editable); stop.setEnabled(view.active() && view.state() != GatewaySession.State.STOPPING);
        for (JComponent component : new JComponent[]{command, peer, destination, delay, diagnostics, load, save}) component.setEnabled(editable);
        led.setEnabled(view.state() == GatewaySession.State.RUNNING && !closing); number.setEnabled(led.isEnabled());
        export.setEnabled(!exporting && !closing);
        device.setSample(view.latest());
        GatewaySession.Sample sample = view.latest();
        StringBuilder text = new StringBuilder("LIVE SNAPSHOT  /  observed HID and authoritative legacy encoder\n\n");
        if (sample == null) text.append("No current report. Stopped or awaiting first complete physical input.\nCaptured evidence remains exportable after Stop.\n");
        else {
            byte[] raw = sample.raw();
            text.append(age).append("\nUTC: ").append(sample.time()).append("\nProvenance: ").append(sample.provenance()).append("\n\nRaw HID (14 bytes, zero-based):\n");
            for (int i = 0; i < raw.length; i++) text.append(String.format("raw[%02d] = %3d   bits %8s\n", i, raw[i] & 255, Integer.toBinaryString((raw[i] & 255) | 256).substring(1)));
            text.append("\nLegacy packet (20 bytes):\n").append(GatewaySession.hex(sample.packet())).append("\n\n");
        }
        text.append("UI names describe observed controls; transmitted flags retain legacy semantics.\nRaw 0..6 normalize to legacy indices 1..7. Throttle raw 1 is device-confirmed.\nBrake A/LJ raw 2 and Brake B/MLJ raw 3: physical mapping unverified.\nBail-off raw 4 shown separately; general button bits are not forwarded.\nLegacy byte 16: direction + key; byte 18: LB3/LB4; byte 19: constant 8 + RCC.\n");
        replaceText(packets, text.toString());
        replaceText(events, "Bounded event log • " + view.eventsEvicted() + " older events evicted • " + view.diagnosticsDropped() + " diagnostic messages dropped\n\n" + String.join("\n", view.events()) + "\n\nRun summaries (" + view.runSummariesEvicted() + " evicted; evicted sends " + view.evictedRunSends() + ", drops " + view.evictedRunDrops() + ")\n"
                + view.runs().stream().map(run -> "Run " + run.run() + ": " + run.successfulSends() + " successful sends, " + run.diagnosticsDropped() + " diagnostic drops, " + run.outcome()).collect(java.util.stream.Collectors.joining("\n")));
        if (closing && !view.active() && pendingFiles == 0 && !closed) { closed = true; timer.stop(); onClosed.run(); }
    }
    private static void replaceText(JTextArea area, String text) {
        if (!area.getText().equals(text)) { int caret = area.getCaretPosition(); area.setText(text); area.setCaretPosition(Math.min(caret, text.length())); }
    }
    private void showError(String title, Throwable failure) { if (!closing && !closed) notices.show(title, failure.getMessage(), JOptionPane.ERROR_MESSAGE); }
    private static JButton button(String label, int mnemonic) { JButton button = new JButton(label); button.setMnemonic(mnemonic); return button; }
    private static JTextArea area(boolean mono) {
        JTextArea area = new JTextArea(); area.setEditable(false);
        area.setFont(new Font(mono ? Font.MONOSPACED : Font.SANS_SERIF, Font.PLAIN, 13));
        area.setLineWrap(!mono); area.setWrapStyleWord(true); area.setOpaque(false); return area;
    }
}
