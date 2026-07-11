package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public final class ProjectedEntityRendererCleanupTest {
    @Test
    public void failedRestoresRemainPendingUntilACompletedRetry() {
        Map<String, Integer> pending = new LinkedHashMap<String, Integer>();
        pending.put("restored", Integer.valueOf(1));
        pending.put("ownership-race", Integer.valueOf(2));

        boolean firstComplete = ProjectedEntityRenderer.removeCompletedRestores(pending,
            value -> value.intValue() == 1);

        assertFalse(firstComplete);
        assertEquals(Map.of("ownership-race", Integer.valueOf(2)), pending);

        boolean retryComplete = ProjectedEntityRenderer.removeCompletedRestores(pending, value -> true);

        assertTrue(retryComplete);
        assertTrue(pending.isEmpty());
    }
}
