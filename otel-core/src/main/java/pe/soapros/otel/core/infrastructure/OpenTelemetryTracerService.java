package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
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
        // 🔧 CORREGIDO: Usar el contexto actual como padre
        Context currentContext = Context.current();

        SpanBuilder spanBuilder = tracer.spanBuilder(name);

        // Si hay un contexto padre, usarlo
        if (currentContext != Context.root()) {
            spanBuilder.setParent(currentContext);
        }

        Span span = spanBuilder.startSpan();

        // Agregar atributos si existen
        if (attributes != null) {
            attributes.forEach(span::setAttribute);
        }

        return new OpenTelemetryTraceSpan(span);
    }

    @Override
    public TraceSpan startSpan(String spanName) {
        Context currentContext = Context.current();
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName);

        if (currentContext != Context.root()) {
            spanBuilder.setParent(currentContext);
        }

        Span span = spanBuilder.startSpan();
        return new OpenTelemetryTraceSpan(span);
    }

    @Override
    public TraceSpan startSpan(String spanName, TraceSpan parent) {
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName);

        if (parent instanceof OpenTelemetryTraceSpan otelParent) {
            // Usar el contexto del span padre
            Context parentContext = otelParent.getContext();
            spanBuilder.setParent(parentContext);
        }

        Span span = spanBuilder.startSpan();
        return new OpenTelemetryTraceSpan(span);
    }

    public TraceSpan startSpanWithContext(String name, Context parentContext, Map<String, String> attributes) {
        SpanBuilder spanBuilder = tracer.spanBuilder(name);

        if (parentContext != null && parentContext != Context.root()) {
            spanBuilder.setParent(parentContext);
        }else {
            spanBuilder.setParent(Context.current());
        }

        Span span = spanBuilder.startSpan();

        if (attributes != null) {
            attributes.forEach(span::setAttribute);
        }

        return new OpenTelemetryTraceSpan(span);
    }

    @Override
    public void endSpan() {

    }

    
    public Tracer getTracer() {
        return tracer;
    }
}