package com.bitdata.sqlworkbench.optimization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceExperimentTasksOrderTest {
    @Test
    void scheduleHasTwoWarmupPairsAndTenBalancedMeasuredPairs() {
        List<String> orders = PerformanceExperimentTasks.orders("42");
        assertEquals(12, orders.size());
        assertEquals(List.of("AB", "BA"), orders.subList(0, 2));
        var measured = orders.subList(2, 12);
        assertEquals(5, measured.stream().filter("AB"::equals).count());
        assertEquals(5, measured.stream().filter("BA"::equals).count());
        assertEquals(orders, PerformanceExperimentTasks.orders("42"));
        assertTrue(PerformanceExperimentTasks.orders("43").subList(2, 12).stream().allMatch(o -> o.equals("AB") || o.equals("BA")));
    }
}
