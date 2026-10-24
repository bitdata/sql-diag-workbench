package com.bitdata.sqlworkbench.execution;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes EXPLAIN parse requests to the correct dialect-specific adapter.
 *
 * <p>Spring auto-discovers all {@link ExplainService} beans at startup and
 * indexes them by {@link ExplainService#dialect()}. Rejects unknown dialects.
 */
@Component
public class ExplainServiceFactory {

    private final Map<String, ExplainService> adapters;

    public ExplainServiceFactory(List<ExplainService> services) {
        this.adapters = services.stream()
            .collect(Collectors.toMap(
                s -> s.dialect().toLowerCase(),
                Function.identity(),
                (a, b) -> a
            ));

    }

    /**
     * Get the ExplainService for the given dialect.
     * Rejects dialects without an explicit adapter.
     */
    public ExplainService get(String dialect) {
        ExplainService adapter = dialect==null ? null : adapters.get(dialect.toLowerCase(java.util.Locale.ROOT));
        if(adapter==null) throw new IllegalArgumentException("APPLICATION_CHANNEL_UNSUPPORTED");
        return adapter;
    }
    public boolean supports(String dialect) { return dialect!=null && adapters.containsKey(dialect.toLowerCase(java.util.Locale.ROOT)); }
}
