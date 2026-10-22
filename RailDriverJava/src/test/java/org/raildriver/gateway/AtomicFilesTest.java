package org.raildriver.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir Path directory;
    @Test void partialWriterFailurePreservesOriginalAndRemovesTemporaryFile() throws Exception {
        Path target = directory.resolve("capture.csv"); Files.writeString(target,"prior valid capture\n");
        assertThrows(IOException.class, () -> AtomicFiles.replace(target,writer -> {
            writer.write("partial invalid replacement"); throw new IOException("synthetic disk write failed");
        }));
        assertEquals("prior valid capture\n",Files.readString(target));
        try(var files = Files.list(directory)) { assertEquals(1,files.count()); }
        AtomicFiles.replace(target,writer -> writer.write("complete replacement\n"));
        assertEquals("complete replacement\n",Files.readString(target));
        GatewayConfig.defaults().save(target); assertEquals(GatewayConfig.defaults(),GatewayConfig.load(target));
        try(var files = Files.list(directory)) { assertEquals(1,files.count()); }
    }
}
