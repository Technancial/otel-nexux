package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;
import io.opentelemetry.api.GlobalOpenTelemetry;

/**
 * Ejemplo simple de función Lambda HTTP usando HttpTracingLambdaWrapper.
 */
public class HttpLambdaExample extends HttpTracingLambdaWrapper {

    public HttpLambdaExample() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    public APIGatewayProxyResponseEvent handle(
            APIGatewayProxyRequestEvent event, Context context) {
        
        // El wrapper ya ha creado el span y configurado las métricas
        System.out.println("Procesando request HTTP");
        System.out.println("Path: " + event.getPath());
        System.out.println("Method: " + event.getHttpMethod());
        
        // Tu lógica de negocio aquí
        String result = processRequest(event);
        
        // Crear respuesta simple
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody("{\"message\":\"" + result + "\"}");
        
        return response;
    }

    private String processRequest(APIGatewayProxyRequestEvent event) {
        // Simular procesamiento
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Processing interrupted";
        }
        
        return "Request processed successfully";
    }
}