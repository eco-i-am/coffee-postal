package ph.coffeepostal.pos.shared;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Two connection pools to the same database. The normal pool is untouched. The practice pool's connections are set to
 * "practice, public" when they are opened, so a practice request reads and writes the practice copies of the purchase order,
 * invoice and stock tables and the real tables for everything else (items, users, branches). The pool never changes
 * its path, so a connection can never carry practice mode back into normal work.
 */
@Configuration(proxyBeanMethods = false)
class PracticeDataSourceConfig {

    static final class Routing extends AbstractRoutingDataSource implements AutoCloseable {
        private final HikariDataSource main;
        private final HikariDataSource practice;

        Routing(HikariDataSource main, HikariDataSource practice) {
            this.main = main;
            this.practice = practice;
            setTargetDataSources(Map.of("main", main, "practice", practice));
            setDefaultTargetDataSource(main);
            afterPropertiesSet();
        }

        @Override
        protected Object determineCurrentLookupKey() { return PracticeContext.on() ? "practice" : "main"; }

        @Override
        public void close() {
            practice.close();
            main.close();
        }
    }

    @Bean
    @Primary
    Routing dataSource(ObjectProvider<JdbcConnectionDetails> details, Environment env,
                       @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        // Tests (Testcontainers) supply connection details as a bean; in production they come from the spring.datasource.* settings.
        JdbcConnectionDetails d = details.getIfAvailable();
        String url = d != null ? d.getJdbcUrl() : env.getRequiredProperty("spring.datasource.url");
        String user = d != null ? d.getUsername() : env.getProperty("spring.datasource.username");
        String password = d != null ? d.getPassword() : env.getProperty("spring.datasource.password");
        HikariDataSource main = pool(url, user, password, "cp-main", poolSize);
        HikariDataSource practice = pool(url, user, password, "cp-practice", 3);
        practice.setMinimumIdle(0);
        practice.setConnectionInitSql("SET search_path TO practice, public");
        return new Routing(main, practice);
    }

    private static HikariDataSource pool(String url, String user, String password, String name, int size) {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).url(url).username(user).password(password).build();
        ds.setPoolName(name);
        ds.setMaximumPoolSize(size);
        return ds;
    }
}
