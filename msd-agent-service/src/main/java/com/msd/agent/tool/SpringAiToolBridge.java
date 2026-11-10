package com.msd.agent.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Spring AI @Tool 注解的普通 Java 对象适配为 AgentScope AgentTool。
 *
 * <p>这里不依赖 Spring AI 的 ToolCallback/victools：Spring AI 2.0 使用 victools 5（Jackson 3），
 * AgentScope 2.0.4 使用 victools 4（Jackson 2），两者运行时不兼容。桥接层只读取
 * Spring AI 注解，直接反射调用方法，并生成最小 JSON Schema。</p>
 */
public final class SpringAiToolBridge {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(SpringAiToolBridge.class);

    /**
     * 工具类禁止实例化。
     */
    private SpringAiToolBridge() {
    }

    /**
     * 扫描 Spring AI @Tool 方法并转换为 AgentScope AgentTool 列表。
     */
    public static List<AgentTool> adapt(Object toolObject) {
        return adapt(toolObject, null);
    }

    /**
     * 扫描并包装统一 ToolGuard；guard 为空时保持旧行为。
     */
    public static List<AgentTool> adapt(Object toolObject, ToolGuard guard) {
        // 1. 取原始用户类，避免 CGLIB 代理丢失方法注解
        Class<?> userClass = ClassUtils.getUserClass(toolObject);
        List<AgentTool> tools = new ArrayList<>();
        // 2. 扫描全部 public 方法，跳过桥接和编译器生成方法
        for (Method method : userClass.getMethods()) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            Tool tool = AnnotationUtils.findAnnotation(method, Tool.class);
            if (tool != null) {
                tools.add(new SpringAiToolAdapter(toolObject, method, tool, guard));
            }
        }
        // 3. 一个工具都没有说明注解写错，直接失败便于启动期发现
        if (tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Spring AI @Tool methods found in " + userClass.getName());
        }
        return tools;
    }

    /**
     * 单个 Spring AI @Tool 方法的 AgentScope 适配器。
     */
    private static final class SpringAiToolAdapter extends ToolBase {

        // 工具方法所属的目标对象
        private final Object toolObject;
        // 被 @Tool 标注的方法
        private final Method method;
        // 方法参数元数据，用于按名称映射模型入参
        private final Parameter[] parameters;
        // 统一工具权限沙箱
        private final ToolGuard guard;

        /**
         * 包装 @Tool 方法，生成 AgentScope 工具定义并缓存参数信息。
         */
        private SpringAiToolAdapter(Object toolObject, Method method, Tool tool,
                ToolGuard guard) {
            super(ToolBase.builder()
                    .name(StringUtils.hasText(tool.name()) ? tool.name() : method.getName())
                    .description(tool.description())
                    .inputSchema(buildSchema(method))
                    .readOnly(true)
                    .concurrencySafe(true)
                    .returnDirect(tool.returnDirect()));
            this.toolObject = toolObject;
            this.method = method;
            this.parameters = method.getParameters();
            this.guard = guard;
        }

        /**
         * 把 AgentScope 参数 Map 反射映射到方法参数并执行。
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> invoke(param))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        /**
         * 实际工具调用；异常转成错误结果，避免中断 Agent 循环。
         */
        private ToolResultBlock invoke(ToolCallParam param) {
            long startNanos = System.nanoTime();
            Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
            try {
                // 1. 统一沙箱先校验工具名、ID、分页和文本长度
                if (guard != null) {
                    guard.validate(getName(), input);
                }

                // 2. 按参数名把模型入参逐个转换成方法参数类型
                Object[] arguments = new Object[parameters.length];
                for (int index = 0; index < parameters.length; index += 1) {
                    Parameter parameter = parameters[index];
                    arguments[index] = convert(
                            input.get(parameter.getName()), parameter.getType(), parameter.getName());
                }
                // 3. 反射执行目标方法，结果统一转成文本
                Object result = method.invoke(toolObject, arguments);
                String text = result == null ? "" : String.valueOf(result);
                audit(param, startNanos, "success", text.length());
                return ToolResultBlock.text(text);
            } catch (ToolAccessException ex) {
                // 4. 沙箱拒绝时不执行目标方法，返回可读原因给模型
                audit(param, startNanos, "rejected", 0);
                log.warn("Spring AI tool rejected: tool={}, reason={}", getName(), ex.getMessage());
                return ToolResultBlock.error("工具调用被拒绝: " + ex.getMessage());
            } catch (InvocationTargetException ex) {
                // 5. 目标方法自身抛错时取真实 cause，避免只看到反射包装异常
                Throwable cause = ex.getTargetException() == null ? ex : ex.getTargetException();
                audit(param, startNanos, "failed", 0);
                log.warn("Spring AI tool failed: {}", getName(), cause);
                return ToolResultBlock.error("工具执行失败: " + readableMessage(cause));
            } catch (Exception ex) {
                // 6. 参数转换或反射失败同样转成错误结果
                audit(param, startNanos, "failed", 0);
                log.warn("Spring AI tool failed: {}", getName(), ex);
                return ToolResultBlock.error("工具执行失败: " + readableMessage(ex));
            }
        }

        /**
         * 记录工具审计；guard 为空时不产生额外日志。
         */
        private void audit(ToolCallParam param, long startNanos, String outcome, int resultLength) {
            if (guard != null) {
                guard.logCall(getName(), param, startNanos, outcome, resultLength);
            }
        }
    }

    /**
     * 根据方法参数生成简单 JSON Schema，覆盖业务工具使用的标量类型。
     */
    private static Map<String, Object> buildSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        // 1. 每个参数生成 type 和 description，默认必填
        for (Parameter parameter : method.getParameters()) {
            ToolParam toolParam = AnnotationUtils.findAnnotation(parameter, ToolParam.class);
            String name = parameter.getName();
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", jsonType(parameter.getType()));
            if (toolParam != null && StringUtils.hasText(toolParam.description())) {
                property.put("description", toolParam.description());
            }
            properties.put(name, property);
            if (toolParam == null || toolParam.required()) {
                required.add(name);
            }
        }

        // 2. 组装 object Schema，只有存在必填项时才写 required
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * Java 类型到 JSON Schema 基础类型。
     */
    private static String jsonType(Class<?> type) {
        if (type == long.class || type == Long.class
                || type == int.class || type == Integer.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        return "string";
    }

    /**
     * 把模型传入的值转换成 Java 参数类型。
     */
    private static Object convert(Object value, Class<?> type, String name) {
        // 1. null 只允许传给包装类型，基本类型视为缺参
        if (value == null) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("缺少参数: " + name);
            }
            return null;
        }
        // 2. 字符串直接转换
        if (type == String.class) {
            return String.valueOf(value);
        }
        // 3. 数值类型优先按 Number 取值，兼容 JSON 反序列化结果
        if (type == long.class || type == Long.class) {
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        }
        if (type == int.class || type == Integer.class) {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        }
        if (type == short.class || type == Short.class) {
            return value instanceof Number number ? number.shortValue() : Short.parseShort(String.valueOf(value));
        }
        if (type == double.class || type == Double.class) {
            return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
        }
        if (type == float.class || type == Float.class) {
            return value instanceof Number number ? number.floatValue() : Float.parseFloat(String.valueOf(value));
        }
        // 4. 布尔值支持 Boolean 和字符串两种来源
        if (type == boolean.class || type == Boolean.class) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
        }
        // 5. 其余类型要求已是目标类型
        if (type.isInstance(value)) {
            return value;
        }
        throw new IllegalArgumentException("参数类型不匹配: " + name);
    }

    /**
     * 提取可读异常信息。
     */
    private static String readableMessage(Throwable ex) {
        return StringUtils.hasText(ex.getMessage())
                ? ex.getMessage()
                : ex.getClass().getSimpleName();
    }
}
