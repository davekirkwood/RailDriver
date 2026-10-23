package org.raildriver.gateway;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** Legacy local binary transport. A complete immutable frame is published at once. */
public final class UdpGateway implements AutoCloseable {
    private final DatagramSocket sender;
    private final DatagramSocket receiver;
    private final ScheduledExecutorService transmitter;
    private final Thread commands;
    private final IntConsumer display;
    private byte[] frame;
    private boolean unavailablePeerReported;
    private volatile boolean running = true;
    private volatile IOException failure;
    private volatile long successfulSends;

    public UdpGateway(IntConsumer display) throws SocketException {
        this(59982, 59981, 59983, display);
    }

    // Alternative loopback ports for isolated synthetic tests only.
    UdpGateway(int receivePort, int commandPeerPort, int destinationPort, IntConsumer display)
            throws SocketException {
        this(receivePort, commandPeerPort, destinationPort, 10, display);
    }

    UdpGateway(int receivePort, int commandPeerPort, int destinationPort, int delayMillis, IntConsumer display)
            throws SocketException {
        this.display = display;
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try { loopback = InetAddress.getByName("127.0.0.1"); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
        receiver = new DatagramSocket(null);
        DatagramSocket output;
        try {
            receiver.setReuseAddress(false);
            receiver.bind(new InetSocketAddress(loopback, receivePort));
            receiver.connect(loopback, commandPeerPort);
            output = new DatagramSocket();
            output.connect(loopback, destinationPort);
        } catch (SocketException e) {
            receiver.close();
            throw new SocketException("Cannot bind/open legacy UDP ports (only one gateway may run): " + e);
        }
        sender = output;
        transmitter = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "legacy-udp-send"));
        commands = new Thread(this::receiveCommands, "legacy-udp-commands");
        transmitter.scheduleWithFixedDelay(this::send, 0, delayMillis, TimeUnit.MILLISECONDS);
        commands.start();
    }

    public synchronized void publish(byte[] next) {
        if (next.length != 20) throw new IllegalArgumentException("Expected 20-byte frame");
        if (running) frame = next.clone();
    }

    private synchronized void send() {
        if (!running || frame == null) return;
        try { sender.send(new DatagramPacket(frame, frame.length)); successfulSends++; }
        catch (PortUnreachableException absentSimulator) {
            // Connected UDP can receive ICMP errors while the simulator is not listening.
            // Retain the legacy startup order and retry only at the normal scheduled cadence.
            if (!unavailablePeerReported) {
                System.err.println("Simulator UDP port unavailable; continuing at nominal 10 ms cadence (message shown once)");
                unavailablePeerReported = true;
            }
        } catch (IOException e) { fail(e); }
    }

    private void receiveCommands() {
        while (running) {
            // Four bytes distinguish oversized datagrams from exactly three bytes.
            DatagramPacket packet = new DatagramPacket(new byte[4], 4);
            try {
                receiver.receive(packet);
                handleCommand(Arrays.copyOf(packet.getData(), packet.getLength()));
            } catch (PortUnreachableException absentSimulator) {
                // The command peer may start later; receive remains blocking.
            } catch (IOException e) {
                if (running) fail(e);
            } catch (RuntimeException e) {
                fail(new IOException("Command handler failed", e));
            }
        }
    }

    void handleCommand(byte[] data) {
        if (data.length != 3) return;
        if (data[0] == 81) close();
        else if (data[0] == 83) display.accept(Byte.toUnsignedInt(data[1]) * 100 + Byte.toUnsignedInt(data[2]));
    }

    private synchronized void fail(IOException e) { failure = e; close(); }
    public boolean isRunning() { return running; }
    public long successfulSends() { return successfulSends; }
    public IOException failure() { return failure; }

    @Override public synchronized void close() {
        if (!running) return;
        running = false;
        frame = null;
        sender.close();
        receiver.close();
        transmitter.shutdownNow();
    }

    public void awaitStopped() throws InterruptedException {
        commands.join(1000);
        transmitter.awaitTermination(1, TimeUnit.SECONDS);
    }
}
