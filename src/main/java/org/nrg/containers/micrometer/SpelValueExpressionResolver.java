package org.nrg.containers.micrometer;

import io.micrometer.common.annotation.ValueExpressionResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

@Slf4j
public class SpelValueExpressionResolver implements ValueExpressionResolver {

    @Override
    public String resolve(String expression, Object parameter) {
        try {
            SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            ExpressionParser expressionParser = new SpelExpressionParser();
            Expression expressionToEvaluate = expressionParser.parseExpression(expression);
            return expressionToEvaluate.getValue(context, parameter, String.class);
        }
        catch (Exception ex) {
            log.error("Exception occurred while trying to evaluate the SpEL expression [" + expression + "]", ex);
        }
        return parameter.toString();
    }
}
