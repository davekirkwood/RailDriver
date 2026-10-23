package org.raildriver.gateway;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/** Best-effort ongoing diagnostics: one daemon worker and at most one pending message. */
final class BoundedDiagnostics implements AutoCloseable {
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(1);
    private final Thread worker;
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running = true;

    BoundedDiagnostics(Consumer<String> output) {
        worker = new Thread(() -> {
            while (running) {
                try {
                    String message = pending.take();
                    if (running) output.accept(message); else dropped.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    if (!running) return;
                } catch (RuntimeException failedOutput) {
                    // Diagnostics must not affect device processing or create a retry loop.
                    dropped.incrementAndGet();
                }
            }
        }, "raildriver-diagnostics");
        worker.setDaemon(true);
        worker.start();
    }

    void report(String message) {
        if (running && !pending.offer(message)) dropped.incrementAndGet();
    }

    long dropped() { return dropped.get(); }

    @Override public void close() {
        running = false;
        String message;
        while ((message = pending.poll()) != null) dropped.incrementAndGet();
        worker.interrupt();
        // A native console write may ignore interruption. Never join this daemon.
    }
}
