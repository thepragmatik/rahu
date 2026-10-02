package rahu.core.tools;

import java.util.Objects;

/**
 * Tool descriptor sent to the provider (tools.md): name/version, description,
 * and a JSON input schema. Data only — descriptors never execute anything.
 */
public record ToolDescriptor(String name, String description, String jsonSchema) {

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
    }
}
