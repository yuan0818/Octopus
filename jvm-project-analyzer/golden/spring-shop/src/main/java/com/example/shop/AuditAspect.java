package com.example.shop;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuditAspect {
    @Before("execution(* com.example.shop.OrderServiceImpl.create(..))")
    public void beforeCreate() {
    }
}
