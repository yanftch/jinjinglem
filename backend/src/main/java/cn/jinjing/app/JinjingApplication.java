package cn.jinjing.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JinjingApplication {
    public static void main(String[] args) {
        SpringApplication.run(JinjingApplication.class, args);
    }
}
