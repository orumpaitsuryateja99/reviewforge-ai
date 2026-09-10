package ai.reviewforge.demo;

import java.util.HashMap;
import java.util.Map;

/** Small, intentionally plain domain service used by the ReviewForge demo scenarios. */
public final class InventoryService {

    private final Map<String, Integer> stock = new HashMap<>();

    public InventoryService(String sku, int available) {
        stock.put(sku, available);
    }

    public synchronized boolean reserve(String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        int available = stock.getOrDefault(sku, 0);
        if (quantity > available) {
            return false;
        }
        stock.put(sku, available - quantity);
        return true;
    }

    public synchronized int available(String sku) {
        return stock.getOrDefault(sku, 0);
    }
}

