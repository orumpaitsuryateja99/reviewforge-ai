package ai.reviewforge.demo;

import java.math.BigDecimal;

/** Boundary-heavy pricing code for the second ReviewForge demo scenario. */
public final class ShippingQuote {

    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("50.00");
    private static final BigDecimal STANDARD_SHIPPING = new BigDecimal("7.99");

    public BigDecimal shippingFor(BigDecimal subtotal) {
        if (subtotal == null || subtotal.signum() < 0) {
            throw new IllegalArgumentException("subtotal must be non-negative");
        }
        if (subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0) {
            return BigDecimal.ZERO;
        }
        return STANDARD_SHIPPING;
    }
}

