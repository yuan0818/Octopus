package com.example.shop;
public class Order {
    private Long id; private String status; private long amount;
    public Order(long amount) { this.amount = amount; this.status = "NEW"; }
    public Long getId() { return id; }
    public String getStatus() { return status; }
}
