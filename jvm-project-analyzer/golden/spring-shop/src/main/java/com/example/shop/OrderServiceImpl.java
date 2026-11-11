package com.example.shop;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl implements OrderService {
    private final OrderRepository repository;
    private final ApplicationEventPublisher publisher;
    private final Notifier notifier;

    // 单构造函数：隐式 @Autowired 注入
    public OrderServiceImpl(OrderRepository repository, ApplicationEventPublisher publisher, Notifier notifier) {
        this.repository = repository;
        this.publisher = publisher;
        this.notifier = notifier;
    }

    @Override
    public Order create(long amount) {
        Order saved = repository.save(new Order(amount));
        publisher.publishEvent(new OrderCreatedEvent(saved.getId()));
        notifier.notifyAsync(saved.getId());
        return saved;
    }

    @Override
    public int purgeCancelled() {
        return repository.findByStatus("CANCELLED").size();
    }
}
