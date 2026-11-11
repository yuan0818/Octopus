package com.example.shop;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrderRepository extends JpaRepository<Order, Long> {
    java.util.List<Order> findByStatus(String status);
}
