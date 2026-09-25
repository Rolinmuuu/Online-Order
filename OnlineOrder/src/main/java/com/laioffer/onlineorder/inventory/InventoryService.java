package com.laioffer.onlineorder.inventory;

import com.laioffer.onlineorder.platform.OutOfStock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Limited daily stock.
 *
 * <p>Reserving is a single conditional statement per item:
 * {@code UPDATE inventory SET available = available - q WHERE menu_item_id = ? AND available >= q}.
 * Under READ COMMITTED, a second transaction that reaches the same row waits for the first to
 * finish and then re-checks the WHERE clause against the committed value, so two checkouts can
 * never both take the last unit. No SELECT-then-UPDATE, no application-side lock, no
 * SERIALIZABLE retries.
 *
 * <p>Rows are always touched in ascending menu_item_id order. Two orders that share items then
 * request their row locks in the same order and cannot deadlock (A waits for B's lock on item 1
 * while B waits for A's lock on item 2 is impossible).
 */
@Service
public class InventoryService {

    public record Item(long menuItemId, String name, int quantity) {
    }

    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes stock for every limited item, or throws {@link OutOfStock}. Call inside a transaction. */
    public void reserve(List<Item> items) {
        for (Item it : sorted(items)) {
            int updated = jdbc.update(
                    "UPDATE inventory SET available = available - ? WHERE menu_item_id = ? AND available >= ?",
                    it.quantity(), it.menuItemId(), it.quantity());
            if (updated == 0) {
                List<Integer> left = jdbc.queryForList(
                        "SELECT available FROM inventory WHERE menu_item_id = ?", Integer.class, it.menuItemId());
                if (!left.isEmpty()) {
                    throw new OutOfStock(it.menuItemId(), it.name(), left.get(0));
                }
                // no inventory row: the item is not limited
            }
        }
    }

    /** Gives stock back (order cancelled or expired). Call inside a transaction. */
    public void release(List<Item> items) {
        for (Item it : sorted(items)) {
            jdbc.update("UPDATE inventory SET available = available + ? WHERE menu_item_id = ?",
                    it.quantity(), it.menuItemId());
        }
    }

    /** Remaining units of every limited item. */
    public Map<Long, Integer> availability() {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query("SELECT menu_item_id, available FROM inventory",
                rs -> {
                    out.put(rs.getLong(1), rs.getInt(2));
                });
        return out;
    }

    private static List<Item> sorted(List<Item> items) {
        return items.stream().sorted(Comparator.comparingLong(Item::menuItemId)).toList();
    }
}
