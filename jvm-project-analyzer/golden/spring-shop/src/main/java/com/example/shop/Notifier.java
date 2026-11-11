package com.example.shop;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class Notifier {
    @Async
    public void notifyAsync(Long orderId) {
        format(orderId);
    }

    private String format(Long orderId) {
        return "order " + orderId;
    }
}
