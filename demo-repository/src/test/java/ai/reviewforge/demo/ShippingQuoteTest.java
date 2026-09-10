package ai.reviewforge.demo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingQuoteTest {

    private final ShippingQuote quote = new ShippingQuote();

    @Test
    void chargesBelowTheThreshold() {
        assertThat(quote.shippingFor(new BigDecimal("49.99")))
                .isEqualByComparingTo("7.99");
    }

    @Test
    void isFreeAboveTheThreshold() {
        assertThat(quote.shippingFor(new BigDecimal("75.00")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}

