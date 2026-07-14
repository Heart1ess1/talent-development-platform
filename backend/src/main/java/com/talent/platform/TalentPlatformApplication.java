package com.talent.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.talent.platform.persistence")
@EnableScheduling
public class TalentPlatformApplication {
  public static void main(String[] args) { SpringApplication.run(TalentPlatformApplication.class, args); }
}
