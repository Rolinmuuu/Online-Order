package com.laioffer.onlineorder.inventory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** menu_item_id -> units left today, for the items that are limited. */
    @GetMapping("/inventory")
    public Map<Long, Integer> availability() {
        return inventory.availability();
    }
}
