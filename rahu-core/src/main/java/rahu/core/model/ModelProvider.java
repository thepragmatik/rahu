package rahu.core.model;

/**
 * Generation provider port (extensibility.md): implemented by adapters, injected
 * by composition; never implemented by core. Keep vendor DTOs out of core.
 */
public interface ModelProvider {

    /** One non-streaming generation call; must not retry internally. */
    ModelOutcome generate(GenerationRequest request);
}
