package org.raildriver.gateway;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Write completely beside the destination, then replace it; failed writers preserve prior evidence. */
final class AtomicFiles {
    @FunctionalInterface interface Writer { void write(BufferedWriter writer) throws IOException; }
    private AtomicFiles() { }
    static void replace(Path destination, Writer contents) throws IOException {
        Path target = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".raildriver-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { contents.write(writer); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
