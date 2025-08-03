package pe.soapros.otel.traces.infrastructure;

import io.opentelemetry.api.trace.Span;
import pe.soapros.otel.core.domain.TraceSpan;

public class OpenTelemetryTraceSpan implements TraceSpan {
    private final Span span;

    public OpenTelemetryTraceSpan(Span span) {
        this.span = span;
    }

    @Override
    public void addEvent(String event) {
        span.addEvent(event);
    }

    @Override
    public void recordException(Throwable throwable) {
        span.recordException(throwable);
    }

    @Override
    public void end() {
        span.end();
    }

    @Override
    public void close() throws Exception {
        this.end();
    }
}
