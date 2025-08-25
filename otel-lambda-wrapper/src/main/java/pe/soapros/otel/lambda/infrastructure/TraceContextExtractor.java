package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.events.KafkaEvent;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TraceContextExtractor {

    // W3C Trace Context Propagator - El estándar propagator de traces
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();


    /**
     * MÉTODO PRINCIPAL: Extrae contexto de trace a partir de hearders HTTP
     *
     * @param headers Map con los headers HTTP de la petición
     * @return Context de OpenTelemetry con información del padre (si existe)
     */
    public static Context extractFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Context.current();
        }

        try {
            // PASO 1: Crear un TextMapGetter para leer headers
            TextMapGetter<Map<String, String>> getter = createHeaderGetter();

            // PASO 2: Usar el propagator para extraer contexto
            return PROPAGATOR.extract(Context.current(), headers, getter);

            // Log para debugging
            //logContextInfo(extractedContext, headers);

            //return extractedContext;

        } catch (Exception e) {
            System.err.println("🔥 Error extracting trace context: " + e.getMessage());
            return Context.current();
        }
    }

    /**
     * CREACIÓN DEL GETTER: Interfaz para leer headers de forma case-sensitive
     * <p>
     * El TexMapGetter es una interfaz funcional que le dice al propagator
     * cómo leer valores de nuestro Map de headers
     */
    private static TextMapGetter<Map<String, String>> createHeaderGetter() {
        return new TextMapGetter<Map<String, String>>() {

            @Override
            public Iterable<String> keys(Map<String, String> carrier) {
                // Retorna todas las claves disponibles
                return carrier.keySet();
            }

            @Override
            public String get(Map<String, String> carrier, String key) {
                if (carrier == null) {
                    return null;
                }

                // 🔧 BÚSQUEDA EXACTA primero
                String exactMatch = carrier.get(key);
                if (exactMatch != null) {
                    return exactMatch;
                }

                // 🔧 BÚSQUEDA CASE-INSENSITIVE como fallback
                // Los headers HTTP son case-insensitive según RFC 7230
                return carrier.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
        };
    }

    private static void logContextInfo(Context context, Map<String, String> headers) {
        try {
            var span = io.opentelemetry.api.trace.Span.fromContext(context);
            var spanContext = span.getSpanContext();

            if (spanContext.isValid()) {
                System.out.println("✅ EXTRACTED VALID TRACE CONTEXT:");
                System.out.println("   TraceId: " + spanContext.getTraceId());
                System.out.println("   SpanId: " + spanContext.getSpanId());
                System.out.println("   Sampled: " + spanContext.isSampled());
            } else {
                System.out.println("⚠️ NO VALID TRACE CONTEXT FOUND IN HEADERS");
                System.out.println("   Available headers: " + headers.keySet());

                // Buscar headers de trace conocidos
                headers.entrySet().stream()
                        .filter(entry -> entry.getKey().toLowerCase().contains("trace") ||
                                entry.getKey().toLowerCase().contains("span"))
                        .forEach(entry -> System.out.println("   " + entry.getKey() + ": " + entry.getValue()));
            }
        } catch (Exception e) {
            System.err.println("🔥 Error logging context info: " + e.getMessage());
        }
    }

    /**
     * 🎯 NUEVO: Método para crear contexto manual si no hay headers
     */
    public static Context createNewRootContext() {
        return Context.root();
    }

    /**
     * 🔧 NUEVO: Validar si un contexto tiene información de trace válida
     */
    public static boolean hasValidTraceContext(Context context) {
        if (context == null) {
            return false;
        }

        try {
            var span = io.opentelemetry.api.trace.Span.fromContext(context);
            return span.getSpanContext().isValid();
        } catch (Exception e) {
            return false;
        }
    }

    public static Context extractFromSqsMessage(SQSEvent.SQSMessage message) {
        if (message == null) {
            return Context.current();
        }

        Map<String, SQSEvent.MessageAttribute> attributes = message.getMessageAttributes();
        if (attributes == null || attributes.isEmpty()) {
            return Context.current();
        }

        try {
            Map<String, String> headerMap = attributes.entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue().getStringValue() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().getStringValue()
                    ));

            return extractFromHeaders(headerMap);
        } catch (Exception e) {
            System.err.println("Failed to extract trace context from SQS message: " + e.getMessage());
            return Context.current();
        }
    }

    public static Context extractFromKafkaRecord(KafkaEvent.KafkaEventRecord record) {
        if (record == null) {
            return Context.current();
        }

        Map<String, String> headersMap = new HashMap<>();

        try {
            List<Map<String, byte[]>> headers = record.getHeaders();
            if (headers != null) {
                for (Map<String, byte[]> header : headers) {
                    if (header != null) {
                        for (Map.Entry<String, byte[]> entry : header.entrySet()) {
                            if (entry.getKey() != null && entry.getValue() != null) {
                                headersMap.put(entry.getKey(),
                                        new String(entry.getValue(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }

            return extractFromHeaders(headersMap);
        } catch (Exception e) {
            System.err.println("Failed to extract trace context from Kafka record: " + e.getMessage());
            return Context.current();
        }
    }

    public static void injectIntoHeaders(Context context, Map<String, String> headers) {
        if (context == null || headers == null) return;

        try {
            GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(context, headers, (carrier, key, value) -> {
                        if (carrier != null && key != null && value != null) {
                            carrier.put(key, value);
                        }
                    });
        } catch (Exception e) {
            System.err.println("Failed to inject trace context: " + e.getMessage());
        }
    }

    /**
     * Extrae trace ID del contexto (útil para logging correlacionado)
     */
    public static String getTraceId(Context context) {
        if (context == null) return null;

        try {
            return io.opentelemetry.api.trace.Span.fromContext(context)
                    .getSpanContext()
                    .getTraceId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extrae span ID del contexto
     */
    public static String getSpanId(Context context) {
        if (context == null) return null;

        try {
            return io.opentelemetry.api.trace.Span.fromContext(context)
                    .getSpanContext()
                    .getSpanId();
        } catch (Exception e) {
            return null;
        }
    }
}
