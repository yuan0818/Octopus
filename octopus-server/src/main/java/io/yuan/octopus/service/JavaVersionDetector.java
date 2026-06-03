package io.yuan.octopus.service;

import io.yuan.octopus.common.BuildTool;
import io.yuan.octopus.common.JavaVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaVersionDetector {

    private static final Logger log = LoggerFactory.getLogger(JavaVersionDetector.class);

    public JavaVersion detectVersion(Path projectPath, BuildTool buildTool) {
        try {
            return switch (buildTool) {
                case MAVEN -> detectMavenVersion(projectPath);
                case GRADLE -> detectGradleVersion(projectPath);
            };
        } catch (Exception e) {
            log.warn("Failed to detect Java version for project at {}: {}", projectPath, e.getMessage());
            return JavaVersion.defaultVersion();
        }
    }

    private JavaVersion detectMavenVersion(Path projectPath) {
        Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return JavaVersion.defaultVersion();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomPath.toFile());
            doc.getDocumentElement().normalize();

            // 1. Check <properties> → <maven.compiler.release>
            String release = getPropertyValue(doc, "maven.compiler.release");
            if (release != null) {
                return parseVersion(release);
            }

            // 2. Check <properties> → <maven.compiler.source> / <maven.compiler.target>
            String source = getPropertyValue(doc, "maven.compiler.source");
            if (source != null) {
                return parseVersion(source);
            }
            String target = getPropertyValue(doc, "maven.compiler.target");
            if (target != null) {
                return parseVersion(target);
            }

            // 3. Check maven-compiler-plugin configuration → <source> / <target>
            JavaVersion pluginVersion = detectFromCompilerPlugin(doc);
            if (pluginVersion != null) {
                return pluginVersion;
            }

        } catch (Exception e) {
            log.warn("Failed to parse pom.xml at {}: {}", pomPath, e.getMessage());
        }

        return JavaVersion.defaultVersion();
    }

    private String getPropertyValue(Document doc, String propertyName) {
        NodeList propertiesList = doc.getElementsByTagName("properties");
        for (int i = 0; i < propertiesList.getLength(); i++) {
            Element properties = (Element) propertiesList.item(i);
            NodeList children = properties.getElementsByTagName(propertyName);
            if (children.getLength() > 0) {
                String value = children.item(0).getTextContent().trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private JavaVersion detectFromCompilerPlugin(Document doc) {
        NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            NodeList artifactIds = plugin.getElementsByTagName("artifactId");
            if (artifactIds.getLength() > 0
                    && "maven-compiler-plugin".equals(artifactIds.item(0).getTextContent().trim())) {
                NodeList configs = plugin.getElementsByTagName("configuration");
                if (configs.getLength() > 0) {
                    Element config = (Element) configs.item(0);
                    NodeList sourceNodes = config.getElementsByTagName("source");
                    if (sourceNodes.getLength() > 0) {
                        String value = sourceNodes.item(0).getTextContent().trim();
                        if (!value.isEmpty()) {
                            return parseVersion(value);
                        }
                    }
                    NodeList targetNodes = config.getElementsByTagName("target");
                    if (targetNodes.getLength() > 0) {
                        String value = targetNodes.item(0).getTextContent().trim();
                        if (!value.isEmpty()) {
                            return parseVersion(value);
                        }
                    }
                }
            }
        }
        return null;
    }

    private JavaVersion detectGradleVersion(Path projectPath) {
        Path gradleFile = projectPath.resolve("build.gradle");
        if (!Files.exists(gradleFile)) {
            gradleFile = projectPath.resolve("build.gradle.kts");
        }
        if (!Files.exists(gradleFile)) {
            return JavaVersion.defaultVersion();
        }

        try {
            String content = Files.readString(gradleFile);

            // 1. Match java.toolchain.languageVersion or JavaLanguageVersion.of(N)
            Pattern toolchainPattern = Pattern.compile(
                    "(?:java\\.toolchain\\.languageVersion|JavaLanguageVersion\\.of)\\s*[=(]\\s*(?:JavaLanguageVersion\\.of\\s*\\(\\s*)?(\\d+)\\s*\\)?");
            Matcher toolchainMatcher = toolchainPattern.matcher(content);
            if (toolchainMatcher.find()) {
                return parseVersion(toolchainMatcher.group(1));
            }

            // 2. Match sourceCompatibility / targetCompatibility
            Pattern compatPattern = Pattern.compile(
                    "(?:sourceCompatibility|targetCompatibility)\\s*=\\s*(?:'([^']+)'|\"([^\"]+)\"|JavaVersion\\.VERSION_(\\d+)|([\\d.]+))");
            Matcher compatMatcher = compatPattern.matcher(content);
            if (compatMatcher.find()) {
                String value = compatMatcher.group(1);
                if (value == null) value = compatMatcher.group(2);
                if (value == null) value = compatMatcher.group(3);
                if (value == null) value = compatMatcher.group(4);
                if (value != null) {
                    return parseVersion(value);
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse Gradle build file at {}: {}", gradleFile, e.getMessage());
        }

        return JavaVersion.defaultVersion();
    }

    private JavaVersion parseVersion(String versionStr) {
        if (versionStr == null || versionStr.isBlank()) {
            return JavaVersion.defaultVersion();
        }
        try {
            // Handle "1.8" style versions
            if (versionStr.startsWith("1.")) {
                versionStr = versionStr.substring(2);
            }
            // Extract leading digits
            Matcher m = Pattern.compile("(\\d+)").matcher(versionStr);
            if (m.find()) {
                int majorVersion = Integer.parseInt(m.group(1));
                return JavaVersion.fromMajorVersion(majorVersion);
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse version string: {}", versionStr);
        }
        return JavaVersion.defaultVersion();
    }
}
