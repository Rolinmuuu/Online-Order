package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.RestaurantEntity;
import com.laioffer.onlineorder.model.RestaurantDto;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import com.laioffer.onlineorder.service.RestaurantService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;


@ExtendWith(MockitoExtension.class)
public class RestaurantServiceTests {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    private RestaurantService restaurantService;


    @BeforeEach
    void setup() {
        restaurantService = new RestaurantService(restaurantRepository, menuItemRepository);
    }


    @Test
    void getRestaurants_shouldGroupMenuItemsByRestaurant() {
        List<RestaurantEntity> restaurants = List.of(
                new RestaurantEntity(1L, "Burger King", "123 Main St", "408-111-1111", "url1"),
                new RestaurantEntity(2L, "SGD Tofu House", "456 Oak Ave", "408-222-2222", "url2")
        );
        List<MenuItemEntity> menuItems = List.of(
                new MenuItemEntity(1L, 1L, "Whopper", "Burger", 6.39, "url"),
                new MenuItemEntity(2L, 1L, "Chicken Fries", "Fries", 4.89, "url"),
                new MenuItemEntity(3L, 2L, "Soft Tofu", "Tofu", 17.06, "url")
        );
        Mockito.when(restaurantRepository.findAll()).thenReturn(restaurants);
        Mockito.when(menuItemRepository.findAll()).thenReturn(menuItems);

        List<RestaurantDto> result = restaurantService.getRestaurants();

        Assertions.assertEquals(2, result.size());

        RestaurantDto burgerKing = result.stream()
                .filter(r -> r.name().equals("Burger King"))
                .findFirst().orElseThrow();
        Assertions.assertEquals(2, burgerKing.menuItems().size());

        RestaurantDto tofuHouse = result.stream()
                .filter(r -> r.name().equals("SGD Tofu House"))
                .findFirst().orElseThrow();
        Assertions.assertEquals(1, tofuHouse.menuItems().size());
        Assertions.assertEquals("Soft Tofu", tofuHouse.menuItems().get(0).name());
    }


    @Test
    void getRestaurants_shouldReturnEmptyMenuItemsWhenNoMenuForRestaurant() {
        List<RestaurantEntity> restaurants = List.of(
                new RestaurantEntity(1L, "Burger King", "123 Main St", "408-111-1111", "url1")
        );
        Mockito.when(restaurantRepository.findAll()).thenReturn(restaurants);
        Mockito.when(menuItemRepository.findAll()).thenReturn(List.of());

        List<RestaurantDto> result = restaurantService.getRestaurants();

        Assertions.assertEquals(1, result.size());
        Assertions.assertNull(result.get(0).menuItems());
    }
}
