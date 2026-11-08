package app.moting;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PlaybackTraceTest {
    @Test public void traceIsBoundedAndContainsOnlyTypedStateAndNumericErrors() {
        PlaybackTrace trace = new PlaybackTrace(1000);
        String report = "";
        for (int i = 0; i < 40; i++) {
            report = trace.add(PlaybackTrace.Event.BUFFER, 1000 + i * 1000,
                    1, true, 0, 0, 1, true, 0, 0, 0, 0);
        }
        assertEquals(24, report.split("\n").length);
        assertTrue(report.startsWith("+16s BUFFER"));
        assertTrue(report.endsWith("queue=1 item=true"));
        report = trace.add(PlaybackTrace.Event.ERROR, 41000, 0, true,
                0, 0, 1, true, 2, -100, -200, -300);
        assertTrue(report.endsWith("error=2/-100/-200/-300"));
        assertFalse(report.contains("http"));
        assertTrue(report.length() < 4096);
    }
}
