package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.service.MenuItemService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;


@ExtendWith(MockitoExtension.class)
public class MenuItemServiceTests {

    @Mock
    private MenuItemRepository menuItemRepository;

    private MenuItemService menuItemService;


    @BeforeEach
    void setup() {
        menuItemService = new MenuItemService(menuItemRepository);
    }


    @Test
    void getMenuItemsByRestaurantId_shouldReturnAllItemsForRestaurant() {
        long restaurantId = 1L;
        List<MenuItemEntity> expected = List.of(
                new MenuItemEntity(1L, restaurantId, "Whopper", "Burger", 6.39, "url1"),
                new MenuItemEntity(2L, restaurantId, "Chicken Fries", "Fries", 4.89, "url2")
        );
        Mockito.when(menuItemRepository.getByRestaurantId(restaurantId)).thenReturn(expected);

        List<MenuItemEntity> result = menuItemService.getMenuItemsByRestaurantId(restaurantId);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("Whopper", result.get(0).name());
        Assertions.assertEquals("Chicken Fries", result.get(1).name());
    }


    @Test
    void getMenuItemsByRestaurantId_shouldReturnEmptyListWhenNoItems() {
        long restaurantId = 99L;
        Mockito.when(menuItemRepository.getByRestaurantId(restaurantId)).thenReturn(List.of());

        List<MenuItemEntity> result = menuItemService.getMenuItemsByRestaurantId(restaurantId);

        Assertions.assertTrue(result.isEmpty());
    }


    @Test
    void getMenuItemsById_shouldReturnCorrectItem() {
        long itemId = 5L;
        MenuItemEntity expected = new MenuItemEntity(itemId, 1L, "Whopper Meal", "Delicious", 10.59, "url");
        Mockito.when(menuItemRepository.findById(itemId)).thenReturn(Optional.of(expected));

        MenuItemEntity result = menuItemService.getMenuItemsById(itemId);

        Assertions.assertEquals(expected, result);
        Assertions.assertEquals("Whopper Meal", result.name());
        Assertions.assertEquals(10.59, result.price());
    }
}
