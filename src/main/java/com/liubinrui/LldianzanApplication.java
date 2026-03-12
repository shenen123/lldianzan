package com.liubinrui;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.liubinrui.mapper")
@SpringBootApplication
@EnableScheduling
public class LldianzanApplication {

    public static void main(String[] args) {
        SpringApplication.run(LldianzanApplication.class, args);
    }

}
