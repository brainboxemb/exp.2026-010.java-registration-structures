package emb.experiment.ranking;

/** The four RankingIndex implementations compared by Experiment 010. */
public enum RankingIndexKind {
    REBUILD_SORT,
    SORTED_ARRAY,
    TREE_MAP,
    POOLED_AVL;

    public RankingIndex create(int initialCapacity) {
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
