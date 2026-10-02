package rahu.core.model;

import java.util.Objects;

/** Tool descriptor sent to the provider (tools.md schema subset). */
public record ToolDescriptor(String name, String description, String jsonSchema) {

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
    }
}
