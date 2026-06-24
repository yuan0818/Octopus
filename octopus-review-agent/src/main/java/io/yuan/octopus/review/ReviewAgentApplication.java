package io.yuan.octopus.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication(scanBasePackages = {
        "io.yuan.octopus.review",
        "io.yuan.octopus.infra.memory"
})
@EnableJpaRepositories(basePackages = {
        "io.yuan.octopus.infra.memory.repository",
        "io.yuan.octopus.review.repository"
})
public class ReviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewAgentApplication.class, args);
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(
                "io.yuan.octopus.infra.memory.model",
                "io.yuan.octopus.review.entity"
        );
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        // Copy JPA properties from application.yml
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        props.put("hibernate.ddl-auto", "none");
        em.setJpaPropertyMap(props);

        return em;
    }
}
