package com.xander.lab.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Runtime view of {@code docs/auth-policy.yaml}, the single Gateway access-policy source.
 *
 * <p>The scanner remains the authority for coverage. This class only evaluates the same
 * explicit match shapes at runtime and deliberately fails closed for unmatched writes.</p>
 */
@Component
public class GatewayAccessPolicy {

    private static final List<String> RULE_GROUPS = List.of(
            "preflight", "excluded", "prefix_rules", "explicit_private_get",
            "explicit_public_write", "public_get", "public_non_api", "streams", "protected");

    private final List<Rule> rules;

    public GatewayAccessPolicy() {
        this.rules = loadRules();
    }

    public Decision decide(HttpMethod method, String path) {
        String methodName = method == null ? "ANY" : method.name();
        for (Rule rule : rules) {
            if (rule.matches(methodName, path)) {
                return new Decision(rule.access(), rule.tokenLocation());
            }
        }
        if ("GET".equals(methodName) || "HEAD".equals(methodName) || "OPTIONS".equals(methodName)) {
            return new Decision(Access.PUBLIC, "header");
        }
        return new Decision(Access.AUTHENTICATED, "header");
    }

    private List<Rule> loadRules() {
        try {
            JsonNode root = new YAMLMapper().readTree(
                    new ClassPathResource("auth-policy.yaml").getInputStream());
            List<Rule> loaded = new ArrayList<>();
            for (String group : RULE_GROUPS) {
                JsonNode node = root.get(group);
                if (node == null || node.isNull()) {
                    continue;
                }
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        loaded.add(Rule.from(group, item));
                    }
                } else if (node.isObject() && node.has("match")) {
                    loaded.add(Rule.from(group, node));
                }
            }
            return List.copyOf(loaded);
        } catch (IOException ex) {
            throw new IllegalStateException("无法加载 Gateway 鉴权策略 auth-policy.yaml", ex);
        }
    }

    public enum Access {
        PUBLIC, EXCLUDED, AUTHENTICATED, ADMIN
    }

    public record Decision(Access access, String tokenLocation) {
        public boolean requiresToken() {
            return access == Access.AUTHENTICATED || access == Access.ADMIN;
        }
    }

    private record Rule(String group, JsonNode match, Access access, String tokenLocation) {

        static Rule from(String group, JsonNode entry) {
            String configuredAccess = entry.path("access").asText("");
            Access access = switch (configuredAccess.toLowerCase(Locale.ROOT)) {
                case "excluded" -> Access.EXCLUDED;
                case "admin" -> Access.ADMIN;
                case "authenticated" -> Access.AUTHENTICATED;
                default -> Access.PUBLIC;
            };
            if (configuredAccess.isBlank()) {
                access = "public_get".equals(group) || "public_non_api".equals(group)
                        ? Access.PUBLIC : Access.AUTHENTICATED;
            }
            return new Rule(group, entry.path("match"), access,
                    entry.path("token_location").asText("header"));
        }

        boolean matches(String method, String path) {
            JsonNode methodNode = match.get("method");
            if (methodNode != null && !methodNode.isNull()) {
                boolean methodMatches = methodNode.isArray()
                        ? containsMethod(methodNode, method)
                        : methodMatches(methodNode.asText(), method);
                if (!methodMatches) {
                    return false;
                }
            }
            if (match.has("path") && match.path("path").asText().equals(path)) {
                return true;
            }
            if (match.has("path_pattern") && globMatches(match.path("path_pattern").asText(), path)) {
                return true;
            }
            if (match.has("path_prefix") && path.startsWith(match.path("path_prefix").asText())) {
                return true;
            }
            if (match.has("path_prefix_also")
                    && prefixMatches(match.path("path_prefix_also").asText(), path)) {
                return true;
            }
            return match.has("path_contains") && path.contains(match.path("path_contains").asText());
        }

        private boolean prefixMatches(String prefix, String path) {
            return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
        }

        private boolean containsMethod(JsonNode methods, String method) {
            for (JsonNode item : methods) {
                if (methodMatches(item.asText(), method)) {
                    return true;
                }
            }
            return false;
        }

        private boolean methodMatches(String expected, String actual) {
            return "ANY".equalsIgnoreCase(expected) || expected.equalsIgnoreCase(actual);
        }

        private boolean globMatches(String glob, String path) {
            String[] parts = glob.split("\\*", -1);
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < parts.length; i++) {
                regex.append(Pattern.quote(parts[i]));
                if (i < parts.length - 1) {
                    regex.append("[^/]+");
                }
            }
            return Pattern.compile(regex.append("$").toString()).matcher(path).matches();
        }
    }
}
