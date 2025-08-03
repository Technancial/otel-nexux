package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.function.Supplier;

public class TracingUtils {
    private final Tracer tracer;

    public TracingUtils(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Ejecuta una función dentro de un nuevo sub-span, encapsulando contexto y manejo de excepciones.
     *
     * @param spanName nombre del sub-span
     * @param operation operación que será ejecutada dentro del span
     * @param <T> tipo de retorno
     * @return resultado de la operación
     */
    public <T> T withSpan(String spanName, Supplier<T> operation) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            return operation.get();
        } catch (Exception ex) {
            span.recordException(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Ejecuta un bloque de código dentro de un sub-span sin retorno.
     *
     * @param spanName nombre del sub-span
     * @param runnable bloque de código a ejecutar
     */
    public void withSpan(String spanName, Runnable runnable) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            runnable.run();
        } catch (Exception ex) {
            span.recordException(ex);
            throw ex;
        } finally {
            span.end();
        }
    }
}
