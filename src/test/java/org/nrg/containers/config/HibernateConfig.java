package org.nrg.containers.config;

import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.beans.factory.config.PropertiesFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Properties;

@Configuration
public class HibernateConfig {
    @Bean
    public Properties hibernateProperties() throws IOException {
        Properties properties = new Properties();

        // Use HSQLDialect instead of H2Dialect to work around issue
        //  with h2 version 1.4.200 in hibernate < 5.4 (or so)
        //  where the generated statements to drop tables between tests can't be executed
        //  as they do not cascade.
        // See https://hibernate.atlassian.net/browse/HHH-13711
        // Solution from https://github.com/hibernate/hibernate-orm/pull/3093#issuecomment-562752874
        properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
        properties.put("hibernate.hbm2ddl.auto", "create");
        properties.put("hibernate.cache.use_second_level_cache", false);
        properties.put("hibernate.cache.use_query_cache", false);

        PropertiesFactoryBean hibernate = new PropertiesFactoryBean();
        hibernate.setProperties(properties);
        hibernate.afterPropertiesSet();
        return hibernate.getObject();
    }

    @Bean
    public DataSource dataSource() {
        BasicDataSource basicDataSource = new BasicDataSource();
        basicDataSource.setDriverClassName(org.h2.Driver.class.getName());
        basicDataSource.setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        basicDataSource.setUsername("sa");
        return basicDataSource;
    }
}
