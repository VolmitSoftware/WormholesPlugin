package art.arcane.wormholes.util.project.config;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.config.WormholesSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotloadManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void malformedEditKeepsLastKnownGoodAndCorrectedEditRecovers() throws Exception {
        WormholesSettings initial = WormholesSettings.loadAll(tempDir);
        AtomicReference<WormholesSettings> live = new AtomicReference<>(initial);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch corrected = new CountDownLatch(1);
        HotloadManager manager = new HotloadManager(tempDir, Logger.getLogger("HotloadManagerTest"), settings -> {
            live.set(settings);
            callbacks.incrementAndGet();
            corrected.countDown();
        });
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);

        manager.start();
        try {
            Files.writeString(config, "schema = 2\nquality = \"unterminated\n", StandardCharsets.UTF_8);
            Thread.sleep(2_500L);

            assertSame(initial, live.get());
            assertEquals(0, callbacks.get());

            Files.writeString(config, "schema = 2\nquality = \"performance\"\n", StandardCharsets.UTF_8);
            assertTrue(corrected.await(5, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertEquals(VisualQualityProfile.PERFORMANCE, live.get().getVisualQualityProfile());
        } finally {
            manager.stop();
        }
    }
}
