package rahu.core.routing;

import java.util.List;
import java.util.Objects;

/** A named, user-configured model pool (configuration.md); no dynamic widening. */
public record ModelPool(String name, List<PoolModel> models) {

    public ModelPool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(models, "models");
        models = List.copyOf(models);
        long distinctAliases = models.stream().map(PoolModel::alias).distinct().count();
        if (distinctAliases != models.size()) {
            throw new IllegalArgumentException(
                "duplicate alias within pool " + name + " (configuration.md)");
        }
    }

    @Override
    public List<PoolModel> models() {
        return models;
    }
}
