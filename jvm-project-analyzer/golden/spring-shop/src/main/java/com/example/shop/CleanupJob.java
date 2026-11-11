package com.example.shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CleanupJob {
    private OrderService orderService;

    @Autowired
    public void setOrderService(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void purge() {
        orderService.purgeCancelled();
    }
}
