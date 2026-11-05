package com.zcj.control;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Throughput is measured over the working time, not over the uptime. */
class ControlMetricsTest {

    @Test
    void throughputIgnoresTheIdleTimeBeforeAndAfterTheWork() throws Exception {
        ControlMetrics metrics = new ControlMetrics();
        Thread.sleep(300);                      // idle before the first book

        metrics.ingestCall(0);
        Thread.sleep(100);
        metrics.indexCall(0);
        metrics.bookIndexed(100_000_000);       // 1 book in ~0.1 s
        Thread.sleep(300);                      // idle after the last book

        Map<String, Object> m = metrics.toMap();
        double active = (Double) m.get("active_s");
        double perSecond = (Double) m.get("books_per_second");
        assertTrue(active >= 0.09 && active < 0.3, "active_s = " + active);
        assertTrue(perSecond > 3 && perSecond <= 11, "books_per_second = " + perSecond);
        assertEquals(1L, m.get("books_indexed"));
    }

    @Test
    void noBooksMeansZeroThroughput() {
        Map<String, Object> m = new ControlMetrics().toMap();
        assertEquals(0.0, m.get("books_per_second"));
        assertEquals(0.0, m.get("active_s"));
    }
}
