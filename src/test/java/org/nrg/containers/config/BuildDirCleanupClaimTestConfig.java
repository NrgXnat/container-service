package org.nrg.containers.config;

import org.nrg.containers.daos.BuildDirCleanupClaimDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;


/** HibernateConfig is imported so hbm2ddl creates the claim table from the entity. */
@Configuration
@Import({HibernateConfig.class})
public class BuildDirCleanupClaimTestConfig {

    @Bean
    public BuildDirCleanupClaimDao buildDirCleanupClaimDao(final JdbcTemplate jdbcTemplate) {
        return new BuildDirCleanupClaimDao(jdbcTemplate);
    }
}
