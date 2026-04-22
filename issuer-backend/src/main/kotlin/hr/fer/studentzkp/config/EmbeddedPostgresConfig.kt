package hr.fer.studentzkp.config

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

// Active only under the "local" Spring profile. Downloads a Postgres 16 binary
// on first run (~60MB, cached in ~/.embedpostgresql) and starts it on a random
// port inside the JVM. Flyway + JPA see a normal DataSource — schema, JSONB,
// gen_random_uuid() all behave identically to a real Postgres.
//
// Run with:  ./gradlew bootRun --args='--spring.profiles.active=local'
@Configuration
@Profile("local")
class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    fun embeddedPostgres(): EmbeddedPostgres = EmbeddedPostgres.builder().start()

    @Bean
    @Primary
    fun dataSource(pg: EmbeddedPostgres): DataSource = pg.postgresDatabase
}
