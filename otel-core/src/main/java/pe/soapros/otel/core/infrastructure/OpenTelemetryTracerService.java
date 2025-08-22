package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import pe.soapros.otel.core.domain.TraceSpan;
import pe.soapros.otel.core.domain.TracerService;

import java.util.Map;

public class OpenTelemetryTracerService implements TracerService {
    
    private final Tracer tracer;
    
    public OpenTelemetryTracerService(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public TraceSpan startSpan(String name, Map<String, String> attributes) {
        Span span = tracer.spanBuilder(name).startSpan();
        attributes.forEach(span::setAttribute);
        return new OpenTelemetryTraceSpan(span);
    }

    @Override
    public void endSpan() {

    }

    @Override
    public TraceSpan startSpan(String spanName) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        return new OpenTelemetryTraceSpan(span);
    }
    
    @Override
    public TraceSpan startSpan(String spanName, TraceSpan parent) {
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName);
        
        if (parent instanceof OpenTelemetryTraceSpan) {
            OpenTelemetryTraceSpan otelParent = (OpenTelemetryTraceSpan) parent;
            spanBuilder.setParent(otelParent.getContext());
        }
        
        Span span = spanBuilder.startSpan();
        return new OpenTelemetryTraceSpan(span);
    }
    
    public Tracer getTracer() {
        return tracer;
    }
}