package emb.experiment.registration;

/**
 * A deliberately small comparable boundary for the registration storage experiment.
 * Every implementation uses normalized absolute UTC epoch milliseconds and a
 * serial mutation lane. Protocol conversion and event source are outside scope.
 */
public interface ProjectionModel {
    /** Begin one historical LogBook reconstruction on an empty projection. */
    void beginRebuild();
    /** Finalize ranks, then publish a snapshot cursor; replay mutations stay silent. */
    void completeRebuild();

    boolean add(long sequence, int registrationId, long passageMillis);
    boolean revoke(long sequence, int registrationId, long passageMillis);
    boolean setStart(int registrationId, long startEpochMillis);
    boolean deleteStart(int registrationId);

    int visitAfter(long afterSequence, int limit, RegistrationProjection.RowVisitor visitor);
    int visitChangesAfter(long revision, int limit, ChangeJournal.Visitor visitor);

    int size();
    int rankableCount();
    int rowCapacity();
    int rowGrowthEvents();
    int rankGrowthEvents();
    long stateRevision();
    long rankingRevision();
    long lastMutationSequence();
    long lastRevokeSequence();
    long oldestUsableChangeCursor();
}
