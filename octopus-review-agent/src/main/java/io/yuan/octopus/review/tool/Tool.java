package io.yuan.octopus.review.tool;

/**
 * A Tool is a capability the Agent can use during review.
 * Examples: retrieve source code, search methods, call APIs.
 */
public class Tool {

    private String id;
    private String name;
    private String description;
    private String parameters;   // JSON Schema
    private boolean enabled;

    public Tool() {}

    public Tool(String id, String name, String description, String parameters, boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.enabled = enabled;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
