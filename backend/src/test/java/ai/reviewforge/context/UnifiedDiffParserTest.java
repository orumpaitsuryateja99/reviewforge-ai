package ai.reviewforge.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffParserTest {

    private static final String PATCH = """
            @@ -10,6 +10,8 @@ public class OrderService {
                 public void reserve(String sku, int quantity) {
            -        inventory.reserve(sku, quantity);
            +        if (inventory.available(sku)) {
            +            inventory.reserve(sku, quantity);
            +        }
                 }
             }""";

    @Test
    void readsHunkBoundsFromTheHeader() {
        UnifiedDiffParser.ParsedDiff parsed = UnifiedDiffParser.parse(PATCH);

        assertThat(parsed.hunks()).hasSize(1);
        assertThat(parsed.hunks().getFirst().newStart()).isEqualTo(10);
        assertThat(parsed.hunks().getFirst().newCount()).isEqualTo(8);
        assertThat(parsed.hunks().getFirst().newEnd()).isEqualTo(17);
    }

    @Test
    void numbersAddedLinesAgainstTheHeadRevision() {
        UnifiedDiffParser.ParsedDiff parsed = UnifiedDiffParser.parse(PATCH);

        assertThat(parsed.changedLines()).contains(11, 12, 13);
        assertThat(parsed.covers(11, 13)).isTrue();
        assertThat(parsed.covers(30, 40)).isFalse();
    }

    @Test
    void countsSingleLineHunksWithoutAnExplicitLength() {
        UnifiedDiffParser.ParsedDiff parsed = UnifiedDiffParser.parse("""
                @@ -4 +4 @@
                -int total = 0;
                +int total = 1;""");

        assertThat(parsed.changedLines()).containsExactly(4);
        assertThat(parsed.hunks().getFirst().newCount()).isEqualTo(1);
    }

    @Test
    void treatsAnAbsentPatchAsNoChangedLines() {
        assertThat(UnifiedDiffParser.parse(null).changedLines()).isEmpty();
        assertThat(UnifiedDiffParser.parse("  ").hunks()).isEmpty();
    }
}
