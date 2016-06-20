package org.nrg.execution.config;

import org.nrg.execution.rest.CommandRestApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
@Import(CommandTestConfig.class)
public class CommandRestApiTestConfig {
    @Bean
    public CommandRestApi commandRestApi() {
        return new CommandRestApi();
    }
}