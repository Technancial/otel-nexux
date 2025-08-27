package pe.soapros.otel.lambda.infrastructure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.SneakyThrows;
import pe.soapros.otel.core.domain.TraceSpan;
import pe.soapros.otel.core.domain.TracerService;
import pe.soapros.otel.core.infrastructure.OpenTelemetryTraceSpan;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * SUB-SPAN EXECUTOR: Utilidad para ejecutar operaciones dentro de un sub-spans
 *
 * Esta clase proporciona métodos convenientes para crear y manejar sub-spans
 * de forma automática, incluyendo manejo de errores y métricas de rendimiento
 */
public class SubSpanExecutor {
    private final TracerService tracerService;
    private final boolean enableEvents;
    private final boolean enableDurationMetrics;

    public SubSpanExecutor(TracerService tracerService) {
        this(tracerService, true, true);
    }

    public SubSpanExecutor(TracerService tracerService, boolean enableEvents, boolean enableDurationMetrics) {
        this.tracerService = tracerService;
        this.enableEvents = enableEvents;
        this.enableDurationMetrics = enableDurationMetrics;
    }

    /**
     * Ejecutar operación con retorno dentro de un sub-span
     * @param spanName Nombre del sub-span
     * @param attributes Atributos del sub-span
     * @param operation Operación a ejecutar
     * @return Resultado de la operación
     */
    public <T> T executeWithSubSpan(String spanName, Map<String, String> attributes, Supplier<T> operation) {

        TraceSpan subSpan = tracerService.startSpan(spanName, attributes);
        Span otelSubSpan = ((OpenTelemetryTraceSpan)subSpan).getSpan();

        long startTime = System.currentTimeMillis();

        try (Scope subScope = otelSubSpan.makeCurrent()) {
            //agregar evento de inicio si está habilitado
            if (enableEvents) {
                subSpan.addEvent("operation.started", Map.of(
                        "start.timestamp", String.valueOf(startTime),
                        "operation.name", spanName
                ));
            }

            // Ejecutar la operación
            T result = operation.get();

            // Agregar métricas de duración
            long duration = System.currentTimeMillis() -startTime;
            if (enableDurationMetrics) {
                subSpan.addAttribute("operation.duration_ms", String.valueOf(duration));
                subSpan.addAttribute("operation.status", "success");
            }

            // Agregar evento de finalización exitosa
            if (enableEvents) {
                subSpan.addEvent("operation.completed", Map.of(
                        "duration_ms", String.valueOf(duration),
                        "end.timestamp", String.valueOf(System.currentTimeMillis())
                ));
            }

            subSpan.setStatus(true, "Completed in " + duration + "ms");

            return  result;
        } catch (Exception e) {
            handleSubSpanError(subSpan, spanName, startTime, e);
            throw e;
        } finally {
            subSpan.end();
        }
    }

    /**
     * Ejecutar operación sin retorno dentro de un sub-span
     * @param spanName Nombre del sub-span
     * @param attributes Atributos del sub-span
     * @param operation Operación a ejecutar
     */
    public void executeWithSubSpan(String spanName, Map<String, String> attributes, Runnable operation) {
        executeWithSubSpan(spanName, attributes, (Supplier<Object>) () -> {
            operation.run();
            return null;
        });
    }

    @SneakyThrows
    public <T> T executeWithSubSpan(String spanName, Map<String, String> attributes, Callable<T> callable) {

        TraceSpan subSpan = tracerService.startSpan(spanName, attributes);
        Span otelSubSpan = ((OpenTelemetryTraceSpan) subSpan).getSpan();

        long startTime = System.currentTimeMillis();

        try (Scope subScope = otelSubSpan.makeCurrent()) {
            if (enableEvents) {
                subSpan.addEvent("operation.started", Map.of(
                        "start.timestamp", String.valueOf(startTime),
                        "operation.name", spanName
                ));
            }

            T result = callable.call();

            long duration = System.currentTimeMillis() - startTime;
            if (enableDurationMetrics) {
                subSpan.addAttribute("operation.duration_ms", String.valueOf(duration));
                subSpan.addAttribute("operation.status", "success");
            }

            if (enableEvents) {
                subSpan.addEvent("operation.completed", Map.of(
                        "duration_ms", String.valueOf(duration)
                ));
            }

            subSpan.setStatus(true, "Completed in " + duration + "ms");

            return result;
        } catch (Exception e) {
            handleSubSpanError(subSpan, spanName, startTime, e);
            throw e;
        } finally {
            subSpan.end();
        }
    }

    /**
     * 🔧 Builder pattern para configuración fluida
     */
    public SubSpanBuilder span(String name) {
        return new SubSpanBuilder(name);
    }

    public class SubSpanBuilder {
        private final String spanName;
        private Map<String, String> attributes = Map.of();
        private boolean eventsEnabled = SubSpanExecutor.this.enableEvents;
        private boolean metricsEnabled = SubSpanExecutor.this.enableDurationMetrics;

        private SubSpanBuilder(String spanName) {
            this.spanName = spanName;
        }

        public SubSpanBuilder withAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
            return this;
        }

        public SubSpanBuilder withAttribute(String key, String value) {
            var newAttributes = new java.util.HashMap<>(this.attributes);
            newAttributes.put(key, value);
            this.attributes = Map.copyOf(newAttributes);
            return this;
        }

        public SubSpanBuilder enableEvents(boolean enable) {
            this.eventsEnabled = enable;
            return this;
        }

        public SubSpanBuilder enableMetrics(boolean enable) {
            this.metricsEnabled = enable;
            return this;
        }

        public <T> T execute(Supplier<T> operation) {
            // Crear executor temporal con configuración específica
            SubSpanExecutor tempExecutor = new SubSpanExecutor(
                    SubSpanExecutor.this.tracerService,
                    eventsEnabled,
                    metricsEnabled
            );
            return tempExecutor.executeWithSubSpan(spanName, attributes, operation);
        }

        public void execute(Runnable operation) {
            execute((Supplier<Object>) () -> {
                operation.run();
                return null;
            });
        }

        public <T> T execute(Callable<T> callable) throws Exception {
            SubSpanExecutor tempExecutor = new SubSpanExecutor(
                    SubSpanExecutor.this.tracerService,
                    eventsEnabled,
                    metricsEnabled
            );
            return tempExecutor.executeWithSubSpan(spanName, attributes, callable);
        }
    }

    // =============== MÉTODOS PRIVADOS ===============

    /**
     * 🔥 Manejar errores en sub-spans
     */
    private void handleSubSpanError(TraceSpan subSpan, String spanName, long startTime, Exception e) {
        long duration = System.currentTimeMillis() - startTime;

        if (enableDurationMetrics) {
            subSpan.addAttribute("operation.duration_ms", String.valueOf(duration));
            subSpan.addAttribute("operation.status", "error");
            subSpan.addAttribute("error.type", e.getClass().getSimpleName());
        }

        if (enableEvents) {
            subSpan.addEvent("operation.failed", Map.of(
                    "error.message", e.getMessage() != null ? e.getMessage() : "No message",
                    "error.type", e.getClass().getSimpleName(),
                    "duration_ms", String.valueOf(duration)
            ));
        }

        subSpan.recordException(e);
        subSpan.setStatus(false, "Failed: " + e.getMessage());
    }

    // =============== FACTORY METHODS ===============

    /**
     * 🏭 Crear con configuración estándar
     */
    public static SubSpanExecutor standard(TracerService tracerService) {
        return new SubSpanExecutor(tracerService);
    }

    /**
     * 🏭 Crear con configuración mínima (sin eventos, solo duración)
     */
    public static SubSpanExecutor minimal(TracerService tracerService) {
        return new SubSpanExecutor(tracerService, false, true);
    }

    /**
     * 🏭 Crear con configuración completa (eventos + métricas)
     */
    public static SubSpanExecutor verbose(TracerService tracerService) {
        return new SubSpanExecutor(tracerService, true, true);
    }
}
