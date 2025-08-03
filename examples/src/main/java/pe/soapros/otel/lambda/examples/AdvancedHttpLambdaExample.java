package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Ejemplo avanzado que demuestra cómo agregar spans anidados y eventos personalizados.
 */
public class AdvancedHttpLambdaExample extends HttpTracingLambdaWrapper {

    private final Tracer tracer = GlobalOpenTelemetry.get()
            .getTracer("pe.soapros.otel.lambda.examples.advanced");

    public AdvancedHttpLambdaExample() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    protected APIGatewayProxyResponseEvent handleHttpRequest(
            APIGatewayProxyRequestEvent event, Context context) {
        
        // Obtener el span principal creado por el wrapper
        Span rootSpan = Span.current();
        
        // Agregar eventos y atributos al span principal
        rootSpan.addEvent("Iniciando procesamiento avanzado");
        rootSpan.setAttribute("request.method", event.getHttpMethod());
        rootSpan.setAttribute("request.path", event.getPath());
        
        try {
            // Span para validación de entrada
            String validationResult = performValidation(event);
            
            // Span para lógica de negocio
            String businessResult = performBusinessLogic(event, validationResult);
            
            // Span para persistencia
            String persistResult = performPersistence(businessResult);
            
            rootSpan.addEvent("Procesamiento completado exitosamente");
            
            // Crear respuesta
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(200);
            response.setBody("{\"result\":\"" + persistResult + "\",\"status\":\"success\"}");
            
            return response;
            
        } catch (Exception e) {
            rootSpan.recordException(e);
            rootSpan.addEvent("Error en el procesamiento");
            
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(500);
            response.setBody("{\"error\":\"Processing failed\",\"message\":\"" + e.getMessage() + "\"}");
            
            return response;
        }
    }
    
    private String performValidation(APIGatewayProxyRequestEvent event) {
        // Crear span específico para validación
        Span validationSpan = tracer.spanBuilder("validate-request")
                .startSpan();
        
        try (Scope scope = validationSpan.makeCurrent()) {
            validationSpan.addEvent("Iniciando validación");
            
            // Simular validación
            if (event.getBody() == null || event.getBody().trim().isEmpty()) {
                validationSpan.addEvent("Validación fallida: body vacío");
                throw new IllegalArgumentException("Request body is required");
            }
            
            validationSpan.setAttribute("validation.body.length", event.getBody().length());
            validationSpan.addEvent("Validación completada exitosamente");
            
            return "validation-passed";
            
        } catch (Exception e) {
            validationSpan.recordException(e);
            throw e;
        } finally {
            validationSpan.end();
        }
    }
    
    private String performBusinessLogic(APIGatewayProxyRequestEvent event, String validationResult) {
        // Crear span específico para lógica de negocio
        Span businessSpan = tracer.spanBuilder("business-processing")
                .startSpan();
        
        try (Scope scope = businessSpan.makeCurrent()) {
            businessSpan.addEvent("Iniciando lógica de negocio");
            businessSpan.setAttribute("validation.result", validationResult);
            
            // Simular procesamiento complejo
            Thread.sleep(100); // Simular tiempo de procesamiento
            
            String result = "processed-" + System.currentTimeMillis();
            businessSpan.setAttribute("business.result.id", result);
            businessSpan.addEvent("Lógica de negocio completada");
            
            return result;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            businessSpan.recordException(e);
            throw new RuntimeException("Business processing interrupted", e);
        } catch (Exception e) {
            businessSpan.recordException(e);
            throw e;
        } finally {
            businessSpan.end();
        }
    }
    
    private String performPersistence(String businessResult) {
        // Crear span específico para persistencia
        Span persistenceSpan = tracer.spanBuilder("persist-data")
                .startSpan();
        
        try (Scope scope = persistenceSpan.makeCurrent()) {
            persistenceSpan.addEvent("Iniciando persistencia");
            persistenceSpan.setAttribute("data.to.persist", businessResult);
            
            // Simular operación de base de datos
            Thread.sleep(50);
            
            String persistId = "persist-" + businessResult;
            persistenceSpan.setAttribute("persistence.id", persistId);
            persistenceSpan.addEvent("Datos persistidos exitosamente");
            
            return persistId;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            persistenceSpan.recordException(e);
            throw new RuntimeException("Persistence interrupted", e);
        } catch (Exception e) {
            persistenceSpan.recordException(e);
            throw e;
        } finally {
            persistenceSpan.end();
        }
    }
}