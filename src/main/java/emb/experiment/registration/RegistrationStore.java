package emb.experiment.registration;

/**
 * Common API for the registration-storage alternatives in this experiment.
 *
 * One store contains the registrations for one open timing point (WP).
 * Rows stay in original ADD order. A REV marks the matching ADD invalid.
 */
public interface RegistrationStore {
    long MISSING_TIME = -1L;

    interface Visitor {
        void accept(long sequence, int registrationId, long passageMillis,
                    long elapsedMillis, boolean valid, int rank);
    }

    /** Start silent LogBook replay into a new empty store. */
    void beginRebuild();

    /** Finish LogBook replay and make the store readable. */
    void completeRebuild();

    boolean add(long sequence, int registrationId, long passageMillis);
    boolean revoke(long sequence, int registrationId, long passageMillis);

    boolean setStart(int registrationId, long startEpochMillis);
    boolean deleteStart(int registrationId);

    int visitAfter(long afterSequence, int limit, Visitor visitor);
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
