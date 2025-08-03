package pe.soapros.otel.lambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;


public class MockLambdaContext implements Context {
    @Override
    public String getAwsRequestId() {
        return "mock-request-id";
    }

    @Override
    public String getLogGroupName() {
        return "mock-log-group";
    }

    @Override
    public String getLogStreamName() {
        return "mock-log-stream";
    }

    @Override
    public String getFunctionName() {
        return "mock-function";
    }

    @Override
    public String getFunctionVersion() {
        return "1.0";
    }

    @Override
    public String getInvokedFunctionArn() {
        return "arn:aws:lambda:us-east-1:123456789012:function:mock-function";
    }

    @Override
    public CognitoIdentity getIdentity() {
        return null;
    }

    @Override
    public ClientContext getClientContext() {
        return null;
    }

    @Override
    public int getRemainingTimeInMillis() {
        return 300000;
    }

    @Override
    public int getMemoryLimitInMB() {
        return 512;
    }

    @Override
    public LambdaLogger getLogger() {
        return new LambdaLogger() {
            @Override
            public void log(String message) {
                System.out.println(message);
            }

            @Override
            public void log(byte[] message) {
                System.out.println(new String(message));
            }
        };
    }
}
