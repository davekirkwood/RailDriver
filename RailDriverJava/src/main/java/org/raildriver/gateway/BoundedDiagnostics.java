package org.raildriver.gateway;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/** Best-effort ongoing diagnostics: one daemon worker and at most one pending message. */
final class BoundedDiagnostics implements AutoCloseable {
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(1);
    private final Thread worker;
    private volatile boolean running = true;

    BoundedDiagnostics(Consumer<String> output) {
        worker = new Thread(() -> {
            while (running) {
                try {
                    String message = pending.take();
                    if (running) output.accept(message);
                } catch (InterruptedException interrupted) {
                    if (!running) return;
                } catch (RuntimeException failedOutput) {
                    // Diagnostics must not affect device processing or create a retry loop.
                }
            }
        }, "raildriver-diagnostics");
        worker.setDaemon(true);
        worker.start();
    }

    void report(String message) {
        if (running) pending.offer(message);
    }

    @Override public void close() {
        running = false;
        pending.clear();
        worker.interrupt();
        // A native console write may ignore interruption. Never join this daemon.
    }
}
