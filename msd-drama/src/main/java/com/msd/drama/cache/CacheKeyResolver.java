package com.msd.drama.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析注解上的 SpEL key 和布隆 ID。
 */
@Component
public class CacheKeyResolver {

    // SpEL 解析失败日志
    private static final Logger log = LoggerFactory.getLogger(CacheKeyResolver.class);

    // SpEL 表达式解析器
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    // 表达式缓存，避免每次调用都重新解析
    private final Map<String, Expression> expressions = new ConcurrentHashMap<>();

    /**
     * 解析字符串 key；失败时使用方法名 + 参数 hash 兜底，避免缓存故障阻断业务。
     */
    public String resolveKey(String expression, Method method, Object[] args) {
        // 1. 表达式为空时返回固定占位 key
        if (expression == null || expression.isBlank()) {
            return "default";
        }
        try {
            // 2. 解析并缓存表达式，然后代入方法参数求值
            Expression parsed = expressions.computeIfAbsent(expression,
                    expressionParser::parseExpression);
            Object value = parsed.getValue(context(method, args));
            return value == null ? "null" : String.valueOf(value);
        } catch (Exception e) {
            // 3. 解析失败时用方法名 + 参数 hash 兜底，避免缓存故障阻断业务
            log.warn("resolve cache key failed: method={}, expression={}, error={}",
                    method.getName(), expression, e.getMessage());
            return method.getName() + ":" + Arrays.deepHashCode(args);
        }
    }

    /**
     * 解析 Long 值，例如布隆 ID。
     */
    public Long resolveLong(String expression, Method method, Object[] args) {
        // 1. 表达式为空表示不启用
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            // 2. 解析并缓存表达式，然后代入方法参数求值
            Expression parsed = expressions.computeIfAbsent(expression,
                    expressionParser::parseExpression);
            Object value = parsed.getValue(context(method, args));
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            // 3. 解析或转换失败返回 null，由调用方跳过布隆过滤
            log.warn("resolve long value failed: method={}, expression={}, error={}",
                    method.getName(), expression, e.getMessage());
            return null;
        }
    }

    /**
     * 构造 SpEL 上下文：按参数名以及 p0/a0 两种形式注册变量。
     */
    private StandardEvaluationContext context(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        // 1. 优先按编译期保留的参数名注册变量
        String[] names = new DefaultParameterNameDiscoverer().getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
        // 2. 再补充按下标访问的 p0、a0 别名，兼容未保留参数名的场景
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
        }
        return context;
    }
}
