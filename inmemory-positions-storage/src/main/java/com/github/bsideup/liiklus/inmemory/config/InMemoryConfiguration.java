package com.github.bsideup.liiklus.inmemory.config;

import com.github.bsideup.liiklus.config.ExporterProfile;
import com.github.bsideup.liiklus.config.GatewayProfile;
import com.github.bsideup.liiklus.inmemory.InMemoryPositionsStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ExporterProfile
@GatewayProfile
@ConditionalOnProperty(value = "storage.positions.type", havingValue = "MEMORY")
public class InMemoryConfiguration {

    @Bean
    InMemoryPositionsStorage inMemoryPositionsStorage() {
        log.warn("\n" +
                String.format("%0106d", 0).replace("0", "=") + "\n" +
                String.format("%0106d", 0).replace("0", "=") + "\n" +
                String.format("%0106d", 0).replace("0", "=") + "\n" +
                "=== In-memory position storage is used. Please, DO NOT run it in production if you ACK your positions. ===\n" +
                String.format("%0106d", 0).replace("0", "=") + "\n" +
                String.format("%0106d", 0).replace("0", "=") + "\n" +
                String.format("%0106d", 0).replace("0", "=")
        );
        return new InMemoryPositionsStorage();
    }

}
