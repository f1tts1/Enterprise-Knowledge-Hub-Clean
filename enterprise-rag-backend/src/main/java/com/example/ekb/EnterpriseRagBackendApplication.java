package com.example.ekb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({
        "com.example.ekb.user.mapper",
        "com.example.ekb.knowledge.mapper",
        "com.example.ekb.document.mapper",
        "com.example.ekb.indexing.mapper",
        "com.example.ekb.observability.mapper",
//        "com.example.ekb.conversation.mapper"
})
@EnableRabbit
@EnableScheduling
@SpringBootApplication
public class EnterpriseRagBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnterpriseRagBackendApplication.class, args);
    }
}
