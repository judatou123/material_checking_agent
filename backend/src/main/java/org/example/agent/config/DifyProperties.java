package org.example.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dify")
public class DifyProperties {
    private String apiUrl;
    private String apiKey;
    private String materialEntryApiKey;
}
