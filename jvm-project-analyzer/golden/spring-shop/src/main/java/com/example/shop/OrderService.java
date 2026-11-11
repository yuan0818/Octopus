package com.example.shop;
public interface OrderService {
    Order create(long amount);
    int purgeCancelled();
}
