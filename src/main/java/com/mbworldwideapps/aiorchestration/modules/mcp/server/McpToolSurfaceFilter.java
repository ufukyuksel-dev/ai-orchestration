package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mbworldwideapps.aiorchestration.config.McpToolSurfaceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Trims the tools/list answer to the lean tool set for agents that did not ask for the full surface.
 * Only tools/list is touched; every tool remains callable, so full-surface clients and older instructions keep
 * working. Works for both JSON and SSE-framed Streamable HTTP answers.
 */
public class McpToolSurfaceFilter extends OncePerRequestFilter {

    static final String PROFILE_HEADER = "X-AI-Orch-Tools";
    /** A tools/list request is tiny; larger bodies are forwarded without being parsed. */
    private static final int MAX_LIST_REQUEST = 64 * 1024;

    private final McpToolSurfaceProperties properties;
    private final ObjectMapper json;

    public McpToolSurfaceFilter(McpToolSurfaceProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Set<String> keep = "POST".equalsIgnoreCase(request.getMethod()) ? surface(request) : null;
        if (keep == null) {
            chain.doFilter(request, response);
            return;
        }
        // Read the whole body: the downstream handler must receive every byte (e.g. a large last_job.save).
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyRequest cached = new CachedBodyRequest(request, body);
        String method = body.length > MAX_LIST_REQUEST ? "" : method(body);
        boolean minimal = keep == properties.minimalSet() || keep.equals(properties.minimalSet());
        if (!"tools/list".equals(method) && !(minimal && "initialize".equals(method))) {
            chain.doFilter(cached, response);
            return;
        }
        BufferedResponse buffered = new BufferedResponse(response);
        chain.doFilter(cached, buffered);
        byte[] rewritten = "initialize".equals(method) ? rewriteInstructions(buffered.bytes())
                : rewrite(buffered.bytes(), keep);
        response.setContentLength(rewritten.length);
        response.getOutputStream().write(rewritten);
        response.flushBuffer();
    }

    /** The tool names to advertise, or null for the full surface. */
    private Set<String> surface(HttpServletRequest request) {
        String header = request.getHeader(PROFILE_HEADER);
        if (header != null && !header.isBlank()) {
            String profile = header.trim().toLowerCase(Locale.ROOT);
            return "minimal".equals(profile) ? properties.minimalSet() : "lean".equals(profile) ? properties.leanSet() : null;
        }
        String client = request.getHeader("X-AI-Orch-Client");
        if (client != null && properties.fullProfileClients().contains(client.trim())) {
            return null;
        }
        return "lean".equals(properties.profile()) ? properties.leanSet()
                : "minimal".equals(properties.profile()) ? properties.minimalSet() : null;
    }

    private String method(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            return node == null ? "" : node.path("method").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Minimal-profile clients get their session start from a hook, so the server's general instructions ("first call
     * session.bootstrap") would send them looking for a tool they do not have (seen as wasted tool searches).
     */
    static final String MINIMAL_INSTRUCTIONS = "AI Orchestration: this repository's rules and matching memory cards "
            + "arrive with the user's request. Before the final answer call memory.learn once if the task taught reusable "
            + "project knowledge or the user asked you to remember something. If this server fails, continue locally.";

    byte[] rewriteInstructions(byte[] original) {
        String text = new String(original, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(text.length());
        boolean changed = false;
        for (String line : text.split("\n", -1)) {
            String payload = line.startsWith("data:") ? line.substring(5).trim() : line.startsWith("{") ? line : null;
            if (payload != null) {
                try {
                    JsonNode node = json.readTree(payload);
                    if (node != null && node.path("result").isObject() && node.path("result").has("instructions")) {
                        ((ObjectNode) node.path("result")).put("instructions", MINIMAL_INSTRUCTIONS);
                        line = (line.startsWith("data:") ? "data:" : "") + json.writeValueAsString(node);
                        changed = true;
                    }
                } catch (IOException | ClassCastException ignored) {
                    // not JSON: keep the line as it is
                }
            }
            if (out.length() > 0 || changed || !line.isEmpty() || text.startsWith("\n")) out.append(line).append('\n');
        }
        String result = out.toString();
        if (!text.endsWith("\n") && result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return changed ? result.getBytes(StandardCharsets.UTF_8) : original;
    }

    byte[] rewrite(byte[] original) {
        return rewrite(original, properties.leanSet());
    }

    byte[] rewrite(byte[] original, Set<String> keep) {
        String text = new String(original, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(text.length());
        boolean changed = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(original), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) out.append('\n');
                first = false;
                if (line.startsWith("data:")) {
                    String filtered = filterJson(line.substring(5).trim(), keep);
                    if (filtered != null) {
                        out.append("data:").append(filtered);
                        changed = true;
                        continue;
                    }
                } else if (line.startsWith("{")) {
                    String filtered = filterJson(line, keep);
                    if (filtered != null) {
                        out.append(filtered);
                        changed = true;
                        continue;
                    }
                }
                out.append(line);
            }
            if (text.endsWith("\n")) out.append('\n');
        } catch (IOException e) {
            return original;
        }
        return changed ? out.toString().getBytes(StandardCharsets.UTF_8) : original;
    }

    private String filterJson(String payload, Set<String> keep) {
        try {
            JsonNode node = json.readTree(payload);
            JsonNode tools = node == null ? null : node.path("result").path("tools");
            if (tools == null || !tools.isArray()) return null;
            ArrayNode kept = json.createArrayNode();
            tools.forEach(tool -> {
                if (keep.contains(tool.path("name").asText())) kept.add(tool);
            });
            ((ObjectNode) node.path("result")).set("tools", kept);
            return json.writeValueAsString(node);
        } catch (IOException | ClassCastException e) {
            return null;
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class BufferedResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int b) { buffer.write(b); }
            @Override public void write(byte[] b, int off, int len) { buffer.write(b, off, len); }
        };
        private java.io.PrintWriter writer;

        BufferedResponse(HttpServletResponse response) {
            super(response);
        }

        @Override public ServletOutputStream getOutputStream() { return stream; }

        @Override
        public java.io.PrintWriter getWriter() {
            if (writer == null) {
                writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);
            }
            return writer;
        }

        @Override public void setContentLength(int len) { }
        @Override public void setContentLengthLong(long len) { }
        @Override public void flushBuffer() { if (writer != null) writer.flush(); }

        byte[] bytes() {
            if (writer != null) writer.flush();
            return buffer.toByteArray();
        }
    }
}
