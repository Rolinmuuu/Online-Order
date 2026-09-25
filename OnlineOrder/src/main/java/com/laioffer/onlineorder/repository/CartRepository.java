package com.laioffer.onlineorder.repository;

import com.laioffer.onlineorder.entity.CartEntity;
import org.springframework.data.repository.ListCrudRepository;

public interface CartRepository extends ListCrudRepository<CartEntity, Long> {
    CartEntity getByCustomerId(Long customerId);
}
