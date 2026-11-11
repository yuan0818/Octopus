package com.example.shop;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {
    @EventListener
    public void onCreated(OrderCreatedEvent event) {
        audit(event.orderId());
    }

    private void audit(Long orderId) {
    }
}
