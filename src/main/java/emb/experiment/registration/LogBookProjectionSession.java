package emb.experiment.registration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, standalone LogBook / OPEN / CLOSE integration fixture.
 *
 * The historical logbook remains authoritative; one active WP projection is
 * reconstructed from the complete logbook on OPEN. Registration facts from
 * other locations and NODE_OPEN/NODE_CLOSE facts are ignored by projection,
 * mirroring ActiveRegistrations.apply() filtering. Historical replay is silent:
 * a fresh display obtains a full snapshot and stores the returned change cursor.
 *
 * This fixture deliberately does not import production classes or simulate
 * network delivery, serialized Task Lane queues or IF-05 codecs.
 */
public final class LogBookProjectionSession {
    public enum Kind { AUTOMATIC, MANUAL, NODE_OPEN, NODE_CLOSE }
    public enum Action { ADD, REV }

    /** Simplified immutable TimingRecord-like fact; time is UTC epoch milliseconds. */
    public static final class Fact {
        public final long sequence;
        public final int location;
        public final int registrationId;
        public final long passage;
        public final Kind kind;
        public final Action action;

        public Fact(long sequence, int location, Kind kind, Action action,
                    int registrationId, long passage) {
            if (sequence < 1 || location < 1 || kind == null || passage < 0)
                throw new IllegalArgumentException("invalid LogBook fact");
            boolean registration = kind == Kind.AUTOMATIC || kind == Kind.MANUAL;
            if ((registration && (action == null || registrationId == 0))
                    || (!registration && (action != null || registrationId != 0)))
                throw new IllegalArgumentException("invalid action / identity for kind");
            this.sequence = sequence;
            this.location = location;
            this.kind = kind;
            this.action = action;
            this.registrationId = registrationId;
            this.passage = passage;
        }

        boolean isRegistration() {
            return kind == Kind.AUTOMATIC || kind == Kind.MANUAL;
        }
    }

    private final ModelKind modelKind;
    private final int initialCapacity;
    private final List<Fact> logBook = new ArrayList<Fact>();
    private final Map<Integer, Long> retainedStarts = new LinkedHashMap<Integer, Long>();
    private ProjectionModel active;
    private int activeLocation;
    private long newestLogBookSequence;

    public LogBookProjectionSession(ModelKind modelKind, int initialCapacity) {
        if (modelKind == null || initialCapacity < 1)
            throw new IllegalArgumentException("model and capacity required");
        this.modelKind = modelKind;
        this.initialCapacity = initialCapacity;
    }

    /**
     * Durable append simulation. All records are committed first; only then is
     * the active projection updated on the single caller lane.
     */
    public void append(Fact fact) {
        if (fact == null || fact.sequence <= newestLogBookSequence)
            throw new IllegalArgumentException("LogBook sequences must strictly increase");
        logBook.add(fact);
        newestLogBookSequence = fact.sequence;
        if (active != null) apply(active, fact, activeLocation);
    }

    /** OPEN discards the prior projection and replays all committed history. */
    public ProjectionModel open(int location) {
        if (location < 1) throw new IllegalArgumentException("location must be positive");
        ProjectionModel next = modelKind.create(initialCapacity);
        for (Map.Entry<Integer, Long> start : retainedStarts.entrySet())
            next.setStart(start.getKey(), start.getValue());
        next.beginRebuild();
        for (Fact fact : logBook) apply(next, fact, location);
        next.completeRebuild();
        activeLocation = location;
        active = next;
        return active;
    }

    /** CLOSE discards active display state but retains the LogBook and race starts. */
    public void close() {
        active = null;
        activeLocation = 0;
    }

    public boolean setStart(int registrationId, long startEpochMillis) {
        if (startEpochMillis < 0) throw new IllegalArgumentException("start must be UTC epoch ms");
        Long previous = retainedStarts.put(registrationId, startEpochMillis);
        if (previous != null && previous.longValue() == startEpochMillis) return false;
        if (active != null) active.setStart(registrationId, startEpochMillis);
        return true;
    }

    public boolean deleteStart(int registrationId) {
        if (retainedStarts.remove(registrationId) == null) return false;
        if (active != null) active.deleteStart(registrationId);
        return true;
    }

    private static void apply(ProjectionModel target, Fact fact, int location) {
        if (!fact.isRegistration() || fact.location != location) return;
        if (fact.action == Action.ADD) target.add(fact.sequence, fact.registrationId, fact.passage);
        else target.revoke(fact.sequence, fact.registrationId, fact.passage);
    }

    public ProjectionModel active() {
        if (active == null) throw new IllegalStateException("no active WP");
        return active;
    }

    public int activeLocation() { return activeLocation; }
    public int logBookSize() { return logBook.size(); }
    public long newestLogBookSequence() { return newestLogBookSequence; }
}
