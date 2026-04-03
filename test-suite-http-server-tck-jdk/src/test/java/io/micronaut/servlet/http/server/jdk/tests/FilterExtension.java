package io.micronaut.servlet.http.server.jdk.tests;

import io.micronaut.http.server.tck.tests.forms.FormBindingDeadlockTest;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;

public class FilterExtension implements ExecutionCondition {
    private static final ConditionEvaluationResult DISABLED = ConditionEvaluationResult.disabled("DISABLED");

    public FilterExtension() {
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        String testMethodName = context.getTestMethod().map(Method::getName).orElse("");
        if (testClass == FormBindingDeadlockTest.class) {
            if (testMethodName.equals("halfAsyncFail")) {
                return DISABLED; // Servlet will not fail in this scenario
            }
        }
        return ConditionEvaluationResult.enabled(null);
    }

}
