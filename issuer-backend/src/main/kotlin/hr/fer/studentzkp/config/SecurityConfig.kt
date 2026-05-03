package hr.fer.studentzkp.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.security.SecureRandom
import java.util.Base64

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val environment: Environment,
    @Value("\${studentzkp.admin.username:admin}") private val adminUser: String,
    @Value("\${studentzkp.admin.password:}") private val adminPassword: String,
) {

    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    @Bean
    fun securityFilterChain(http: HttpSecurity, corsConfigurationSource: CorsConfigurationSource): SecurityFilterChain {
        val devShortcut = "dev-shortcut" in environment.activeProfiles
        log.info(
            "SecurityConfig: activeProfiles={}, devShortcutPermit={}",
            environment.activeProfiles.joinToString(","),
            devShortcut,
        )

        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource) }
            .httpBasic(Customizer.withDefaults())
            .formLogin { it.disable() }
            .authorizeHttpRequests { auth ->
                // Public, unauthenticated routes — wallets and verifiers hit
                // these without credentials.
                // antMatcher() forces path-based matching instead of Spring 6.4's
                // MvcRequestMatcher default. MvcRequestMatcher requires Spring MVC
                // to have a registered handler for the path — if a controller bean
                // fails to create, its path 401s instead of 404ing, which makes
                // misconfiguration vs. missing-route indistinguishable.
                auth.requestMatchers(
                    antMatcher("/health"),
                    antMatcher("/actuator/health"),
                    antMatcher("/.well-known/**"),
                    antMatcher("/statuslist/**"),
                    // OID4VCI public surface (final_plan §5.1, ROADMAP Phase 1.5).
                    antMatcher("/credential-offer/**"),
                    antMatcher("/token"),
                    antMatcher("/credential"),
                    // Swagger UI + OpenAPI spec (springdoc).
                    antMatcher("/v3/api-docs"),
                    antMatcher("/v3/api-docs/**"),
                    antMatcher("/swagger-ui"),
                    antMatcher("/swagger-ui/**"),
                    antMatcher("/swagger-ui.html"),
                ).permitAll()

                // Dev shortcuts: open when the dev-shortcut profile is active,
                // refused at the firewall otherwise. Defense in depth — the
                // controllers themselves are also @Profile("dev-shortcut").
                if (devShortcut) {
                    auth.requestMatchers(antMatcher("/dev/**")).permitAll()
                } else {
                    auth.requestMatchers(antMatcher("/dev/**")).denyAll()
                }

                // Privileged surfaces: HTTP Basic against the admin user.
                auth.requestMatchers(antMatcher("/integrity/**"), antMatcher("/admin/**")).authenticated()
                auth.anyRequest().denyAll()
            }
        return http.build()
    }

    @Bean
    fun userDetailsService(): UserDetailsService {
        val password = if (adminPassword.isBlank()) {
            // Generate a per-boot password and log it once. Lets `local`-profile
            // dev runs work without configuration; never relied upon in prod
            // (where studentzkp.admin.password / STUDENTZKP_ADMIN_PASSWORD must
            // be set explicitly).
            val generated = randomPassword()
            log.warn(
                "studentzkp.admin.password not set — generated one for this boot. " +
                    "Use it for /integrity/** and /admin/**: '{}'",
                generated,
            )
            generated
        } else {
            adminPassword
        }
        // {noop} prefix tells DelegatingPasswordEncoder we're storing plaintext.
        // For real production: bcrypt/argon2id-hashed credentials in the DB.
        val admin = User.withUsername(adminUser)
            .password("{noop}$password")
            .roles("ADMIN")
            .build()
        return InMemoryUserDetailsManager(admin)
    }

    @Bean
    fun corsConfigurationSource(
        @Value("\${studentzkp.cors.allowed-origins:http://localhost:5173}") allowedOriginsCsv: String,
    ): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = allowedOriginsCsv.split(",").map(String::trim).filter(String::isNotEmpty)
            allowedMethods = listOf("GET", "POST", "OPTIONS")
            allowedHeaders = listOf("*")
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(18).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
