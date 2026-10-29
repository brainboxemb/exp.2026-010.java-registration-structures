package emb.experiment.ranking;

/** The four ranking solutions compared by the experiment. */
public enum RankingKind {
    REBUILD_SORT,
    SORTED_ARRAY,
    TREE_MAP,
    POOLED_AVL;

    public Ranking create(int initialCapacity) {
        switch (this) {
            case REBUILD_SORT:
                return new RebuildSortRanking(initialCapacity);
            case SORTED_ARRAY:
                return new SortedArrayRanking(initialCapacity);
            case TREE_MAP:
                return new TreeMapRanking();
            case POOLED_AVL:
                return new PooledAvlRanking(initialCapacity);
            default:
                throw new AssertionError(this);
        }
    }
}
