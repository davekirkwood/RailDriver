package org.raildriver.gateway;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Owns native HID on one background thread. Swing only pulls immutable views. */
public final class GatewaySession {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }
    @FunctionalInterface interface DeviceFactory { RailDriverDevice open(Consumer<String> diagnostics) throws Exception; }
    public record Sample(Instant time, long monotonicNanos, long run, String provenance, byte[] raw, byte[] packet) {
        public Sample { raw = raw.clone(); packet = packet.clone(); }
        @Override public byte[] raw() { return raw.clone(); }
        @Override public byte[] packet() { return packet.clone(); }
    }
    public record RunSummary(long run, long successfulSends, long diagnosticsDropped, String outcome) { }
    private record CompletedRun(long run, long sent, LongSupplier dropped, String outcome) {
        RunSummary snapshot() { return new RunSummary(run, sent, dropped.getAsLong(), outcome); }
    }
    public record View(State state, String message, Sample latest, long sent, double rate,
                       int captured, long evicted, List<String> events, long eventsEvicted, long diagnosticsDropped,
                       List<RunSummary> runs, long runSummariesEvicted, long evictedRunSends, long evictedRunDrops) {
        public View { events = List.copyOf(events); runs = List.copyOf(runs); }
        public boolean active() { return state == State.STARTING || state == State.RUNNING || state == State.STOPPING; }
    }
    private final DeviceFactory factory;
    private final String provenance;
    private final int capacity, summaryCapacity;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final ArrayDeque<CompletedRun> completedRuns = new ArrayDeque<>();
    private final AtomicInteger pendingNumber = new AtomicInteger(-1);
    private State state = State.STOPPED;
    private String message = "Stopped — no input observed";
    private Sample latest;
    private volatile boolean stopping;
    private volatile UdpGateway udp;
    private LongSupplier currentDrops = () -> 0;
    private boolean currentRecorded;
    private long evicted, eventsEvicted, sent, startedNanos, stoppedNanos, run;
    private long summariesEvicted, evictedRunSends, evictedRunDrops;

    public GatewaySession() { this(RailDriverDevice::new, "physical HID via hid4java; physical mapping partially verified", 5000); }
    GatewaySession(DeviceFactory factory, String provenance, int capacity) { this(factory, provenance, capacity, 200); }
    GatewaySession(DeviceFactory factory, String provenance, int capacity, int summaryCapacity) {
        if (capacity < 1 || summaryCapacity < 1) throw new IllegalArgumentException("Capture/summary capacities must be positive");
        this.factory = factory; this.provenance = provenance; this.capacity = capacity; this.summaryCapacity = summaryCapacity;
    }
    public synchronized View view() {
        if (udp != null && !udp.isRunning() && (state == State.RUNNING || state == State.STARTING))
            transitionStopping(udp.failure() == null ? "Transport stopped — releasing native resources…" : "Stopping: " + udp.failure().getMessage());
        long count = udp == null ? sent : udp.successfulSends();
        double seconds = startedNanos == 0 ? 0 : ((stoppedNanos == 0 ? System.nanoTime() : stoppedNanos) - startedNanos) / 1e9;
        List<RunSummary> runs = new ArrayList<>();
        for (CompletedRun completed : completedRuns) runs.add(completed.snapshot());
        if (run > 0 && !currentRecorded) runs.add(new RunSummary(run,count,currentDrops.getAsLong(),state.name()));
        long dropped = evictedRunDrops;
        for (RunSummary summary : runs) dropped += summary.diagnosticsDropped();
        return new View(state, message, latest, count, seconds <= 0 ? 0 : count / seconds,
                samples.size(), evicted, new ArrayList<>(events), eventsEvicted, dropped,
                runs, summariesEvicted, evictedRunSends, evictedRunDrops);
    }
    public synchronized boolean start(GatewayConfig config) {
        Objects.requireNonNull(config);
        if (view().active()) return false;
        state = State.STARTING; message = "Opening controller and loopback ports…";
        stopping = false; latest = null; sent = 0; startedNanos = 0; stoppedNanos = 0;
        currentDrops = () -> 0; currentRecorded = false; pendingNumber.set(-1); run++;
        event("Starting run " + run + "; " + config);
        new Thread(() -> operate(config), "raildriver-hid-owner").start(); return true;
    }
    private void transitionStopping(String reason) {
        stopping = true; state = State.STOPPING; message = reason; latest = null; pendingNumber.set(-1);
    }
    public synchronized void stop() {
        if (!view().active() || state == State.STOPPING) return;
        transitionStopping("Stopping — releasing HID and UDP resources…"); event("Stop requested");
        UdpGateway transport = udp;
        if (transport != null) {
            // Never acquire the transport monitor on the EDT or while holding this monitor.
            Thread closer = new Thread(transport::close, "raildriver-stop-udp");
            closer.setDaemon(true); closer.start();
        }
    }
    public synchronized boolean led(int number) {
        if (number < 0 || number > 999) throw new IllegalArgumentException("LED test must be 0–999");
        if (view().state() != State.RUNNING) return false;
        pendingNumber.set(number); event("LED " + number + " queued (latest request wins)"); return true;
    }
    private void operate(GatewayConfig config) {
        Throwable problem = null;
        UdpGateway transport = null;
        RailDriverDevice device = null;
        try {
            device = factory.open(config.diagnostics() ? this::event : ignored -> { });
            synchronized (this) { currentDrops = device.diagnosticCounter(); }
            if (stopping) return;
            transport = new UdpGateway(config.commandPort(), config.peerPort(), config.destinationPort(),
                    config.sendDelayMillis(), pendingNumber::set);
            synchronized (this) {
                udp = transport; startedNanos = System.nanoTime();
                if (!stopping) { state = State.RUNNING; message = "Connected — waiting for first complete HID report"; }
            }
            if (stopping) return;
            device.showLogo();
            while (!stopping && transport.isRunning()) {
                byte[] report = device.read();
                if (stopping || !transport.isRunning()) break;
                if (report != null) {
                    byte[] packet = InputMapper.map(report);
                    Sample sample = new Sample(Instant.now(), System.nanoTime(), run, provenance,
                            Arrays.copyOfRange(report, 1, report.length), packet);
                    synchronized (this) {
                        if (stopping) break;
                        latest = sample;
                        if (samples.size() == capacity) { samples.removeFirst(); evicted++; }
                        samples.addLast(sample); message = "Connected — report observed (timeouts may mean unchanged input)";
                    }
                    transport.publish(packet);
                }
                int number = pendingNumber.getAndSet(-1);
                if (number >= 0 && !stopping && transport.isRunning()) {
                    boolean ok = device.tryShowNumber(number);
                    event(ok ? "LED " + number + " driver write accepted; visual confirmation required"
                            : "LED " + number + " write FAILED/short; check controller connection");
                }
            }
            if (transport.failure() != null) throw transport.failure();
        } catch (Exception | LinkageError e) { problem = e; }
        finally {
            synchronized (this) {
                transitionStopping(problem == null ? "Stopping — releasing native resources…" : "Stopping after failure: " + problem.getMessage());
            }
            if (transport != null) { transport.close(); synchronized (this) { stoppedNanos = System.nanoTime(); } }
            if (device != null) {
                try { device.close(); }
                catch (RuntimeException | LinkageError closeFailed) {
                    if (problem == null) problem = closeFailed; else problem.addSuppressed(closeFailed);
                }
            }
            if (transport != null) {
                try { transport.awaitStopped(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            synchronized (this) {
                sent = transport == null ? 0 : transport.successfulSends(); udp = null;
                latest = null; pendingNumber.set(-1);
                state = problem == null ? State.STOPPED : State.ERROR;
                message = problem == null ? "Stopped — all resources released" : "Stopped: " + problem.getMessage();
                if (completedRuns.size() == summaryCapacity) {
                    RunSummary old = completedRuns.removeFirst().snapshot(); summariesEvicted++;
                    evictedRunSends += old.successfulSends(); evictedRunDrops += old.diagnosticsDropped();
                }
                completedRuns.addLast(new CompletedRun(run,sent,currentDrops,state.name())); currentRecorded = true;
                event(message); notifyAll();
            }
        }
    }
    synchronized void event(String text) {
        if (events.size() == 200) { events.removeFirst(); eventsEvicted++; }
        events.addLast(Instant.now() + "  " + text);
    }
    public CompletableFuture<Integer> export(Path path) {
        return CompletableFuture.supplyAsync(() -> {
            List<Sample> copy; View snapshot;
            synchronized (this) { copy = List.copyOf(samples); snapshot = view(); }
            if (copy.isEmpty()) throw new CompletionException(new IOException("Capture is empty — start and receive a valid HID report first"));
            try {
                AtomicFiles.replace(path, writer -> {
                    writer.write("# RailDriver evidence v1; raw=14 unprefixed HID bytes; packet=unchanged 20-byte legacy encoding\n");
                    writer.write("# Samples are observed reports, not a record of every UDP send. Successful sends are counted separately.\n");
                    writer.write("# capture_evicted=" + snapshot.evicted() + "; events_evicted=" + snapshot.eventsEvicted() + "; diagnostic_messages_dropped=" + snapshot.diagnosticsDropped() + "\n");
                    writer.write("# run_summaries_evicted=" + snapshot.runSummariesEvicted() + "; evicted_run_successful_sends=" + snapshot.evictedRunSends() + "; evicted_run_diagnostic_drops=" + snapshot.evictedRunDrops() + "\n");
                    writer.write("# run_summary,run_id,successful_sends,diagnostic_messages_dropped,outcome\n");
                    for (RunSummary summary : snapshot.runs()) writer.write("# run_summary," + summary.run() + "," + summary.successfulSends() + "," + summary.diagnosticsDropped() + "," + summary.outcome() + "\n");
                    for (String event : snapshot.events()) for (String line : event.split("\\R", -1)) writer.write("# event " + line + "\n");
                    writer.write("timestamp_utc,monotonic_nanos,run,provenance,raw_hex,packet_hex\n");
                    for (Sample sample : copy) writer.write(sample.time() + "," + sample.monotonicNanos() + "," + sample.run()
                            + ",\"" + sample.provenance().replace("\"", "\"\"") + "\"," + hex(sample.raw()) + "," + hex(sample.packet()) + "\n");
                });
                return copy.size();
            } catch (IOException e) { throw new CompletionException(e); }
        });
    }
    static String hex(byte[] bytes) { return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes); }
    synchronized boolean awaitIdle(long millis) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000;
        while (view().active()) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }
}
