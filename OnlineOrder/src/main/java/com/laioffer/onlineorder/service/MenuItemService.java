package com.laioffer.onlineorder.service;

import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MenuItemService {
    private final MenuItemRepository menuItemRepository;

    public MenuItemService(MenuItemRepository menuItemRepository) {
        this.menuItemRepository = menuItemRepository;
    }

    public List<MenuItemEntity> getMenuItemsByRestaurantId(Long restaurantId) {
        return menuItemRepository.getByRestaurantId(restaurantId);
    }

    public MenuItemEntity getMenuItemsById(Long id) {
        return menuItemRepository.findById(id).get();
    }
}
