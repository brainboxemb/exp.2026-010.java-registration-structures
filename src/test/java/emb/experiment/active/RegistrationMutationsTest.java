package emb.experiment.active;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RegistrationMutationsTest {
    @Test
    void ringRetainsLatestRevisionsWithoutEventObjects() {
        RegistrationMutations mutations = new RegistrationMutations(3);

        assertEquals(0, mutations.latestRevision());
        assertEquals(1, mutations.oldestAvailableRevision());
        assertEquals(0, mutations.oldestUsableCursor());

        assertEquals(1, mutations.append(RegistrationMutations.Kind.ROW_ADDED, 10, 0));
        assertEquals(2, mutations.append(
                RegistrationMutations.Kind.START_CHANGED,
                0x410001,
                RegistrationMutations.RANKING_CHANGED));
        assertEquals(3, mutations.append(RegistrationMutations.Kind.ROW_REVOKED, 10, 0));

        assertEquals(
                listOf(
                        "1:ROW_ADDED:10:0",
                        "2:START_CHANGED:4259841:1",
                        "3:ROW_REVOKED:10:0"),
                visit(mutations, 0));

        assertEquals(4, mutations.append(RegistrationMutations.Kind.ROW_ADDED, 11, 0));
        assertEquals(2, mutations.oldestAvailableRevision());
        assertEquals(1, mutations.oldestUsableCursor());

        assertThrows(
                RegistrationMutations.HistoryExpiredException.class,
                () -> visit(mutations, 0));

        assertEquals(
                listOf(
                        "2:START_CHANGED:4259841:1",
                        "3:ROW_REVOKED:10:0",
                        "4:ROW_ADDED:11:0"),
                visit(mutations, 1));
    }

    private static List<String> visit(RegistrationMutations mutations, long after) {
        List<String> result = new ArrayList<String>();
        mutations.visitAfter(after, Integer.MAX_VALUE,
                (revision, kind, subject, flags) ->
                        result.add(revision + ":" + kind + ":" + subject + ":" + flags));
        return result;
    }

    private static List<String> listOf(String... values) {
        List<String> result = new ArrayList<String>();
        for (String value : values)
            result.add(value);
        return result;
    }
}
