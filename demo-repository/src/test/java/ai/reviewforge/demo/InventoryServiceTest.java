package ai.reviewforge.demo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryServiceTest {

    @Test
    void reservesAnAvailableQuantity() {
        InventoryService inventory = new InventoryService("book", 5);

        assertThat(inventory.reserve("book", 2)).isTrue();
        assertThat(inventory.available("book")).isEqualTo(3);
    }
}

