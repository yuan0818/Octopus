package com.example.shop;
public class OrderCreatedEvent {
    private final Long orderId;
    public OrderCreatedEvent(Long orderId) { this.orderId = orderId; }
    public Long orderId() { return orderId; }
}
