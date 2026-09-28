package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One small tool that reaches the occasional tools (saved jobs, personal memory, references, adding a rule the user
 * asked for, and session.bootstrap for the user's answer to "load the rules?") for clients on the "minimal" tool profile: their schemas are not sent
 * with every turn, yet the agent can still use them when needed. Each call runs the real tool method, so its scope
 * checks and audit stay exactly the same.
 */
@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExtrasMcpTool {

    static final List<String> OPS = List.of("session.bootstrap", "last_job.get", "last_job.save", "job_memory.save", "job_memory.search",
            "job_memory.get", "personal_memory.save", "personal_memory.search", "reference.read",
            "rules.draft", "rules.preview", "rules.promote");

    private final ApplicationContext context;
    private final ObjectMapper json;
    private volatile Map<String, Target> targets;

    record Target(Object bean, Method method) {}

    public ExtrasMcpTool(ApplicationContext context, ObjectMapper json) {
        this.context = context;
        this.json = json;
    }

    @McpTool(name = "extras", description = "Saved jobs, personal memory, references and adding a rule, only when the user asks for them; session.bootstrap only for the user's answer to loading the rules. op: session.bootstrap | last_job.get | last_job.save | job_memory.save | job_memory.search | job_memory.get | personal_memory.save | personal_memory.search | reference.read | rules.draft | rules.preview | rules.promote; args: that tool's arguments (see skills/contracts: last-job.md, saved-jobs.md, personal-memory.md, references.md, rule-authoring.md).")
    public Object extras(
            @McpToolParam(description = "The operation, e.g. job_memory.search") String op,
            @McpToolParam(description = "The operation's arguments as an object", required = false) Map<String, Object> args) {
        Target target = targets().get(op == null ? "" : op.trim());
        if (target == null) {
            throw new IllegalArgumentException("op must be one of " + String.join(", ", OPS));
        }
        Map<String, Object> given = args == null ? Map.of() : args;
        Parameter[] params = target.method().getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Object raw = given.get(params[i].getName());
            values[i] = raw == null ? null : json.convertValue(raw, json.constructType(params[i].getParameterizedType()));
        }
        try {
            return target.method().invoke(target.bean(), values);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Target> targets() {
        Map<String, Target> found = targets;
        if (found != null) return found;
        found = new HashMap<>();
        for (String name : context.getBeanNamesForAnnotation(org.springframework.stereotype.Component.class)) {
            Object bean = context.getBean(name);
            for (Method method : AopUtils.getTargetClass(bean).getMethods()) {
                McpTool tool = method.getAnnotation(McpTool.class);
                if (tool != null && OPS.contains(tool.name())) {
                    found.put(tool.name(), new Target(bean, AopUtils.selectInvocableMethod(method, bean.getClass())));
                }
            }
        }
        targets = Map.copyOf(found);
        return targets;
    }
}
