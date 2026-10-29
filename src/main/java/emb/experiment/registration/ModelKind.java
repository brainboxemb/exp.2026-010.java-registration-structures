package emb.experiment.registration;

/** Experimental representation choices. Labels are stable for CSV comparisons. */
public enum ModelKind {
    PRODUCTION_EAGER,
    REBUILD,
    SORTED_ARRAY,
    PACKED_SORTED;

    public ProjectionModel create(int initialCapacity) {
        switch (this) {
            case PRODUCTION_EAGER:
                return new ProductionBaseline(initialCapacity);
            case REBUILD:
                return new RegistrationProjection(RegistrationProjection.RankingMode.REBUILD, initialCapacity);
            case SORTED_ARRAY:
                return new RegistrationProjection(RegistrationProjection.RankingMode.SORTED_ARRAY, initialCapacity);
            case PACKED_SORTED:
                return new PackedProjection(initialCapacity);
            default:
                throw new AssertionError(this);
        }
    }

    public ProjectionModel create(int initialCapacity, int historyCapacity) {
        switch (this) {
            case PRODUCTION_EAGER:
                return new ProductionBaseline(initialCapacity, historyCapacity);
            case REBUILD:
                return new RegistrationProjection(
                        RegistrationProjection.RankingMode.REBUILD, initialCapacity, historyCapacity);
            case SORTED_ARRAY:
                return new RegistrationProjection(
                        RegistrationProjection.RankingMode.SORTED_ARRAY, initialCapacity, historyCapacity);
            case PACKED_SORTED:
                return new PackedProjection(initialCapacity, historyCapacity);
            default:
                throw new AssertionError(this);
        }
    }
}
