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
                // Lambda matchers — pure URI prefix checks, no MVC handler dependency.
                // Spring 6.4's MvcRequestMatcher (the default for String overloads)
                // requires an MVC handler for the path, so unmapped paths fall through
                // to anyRequest() — which makes misconfiguration look like 401.
                auth.requestMatchers { req ->
                    val p = req.requestURI
                    p == "/health" ||
                        p.startsWith("/actuator/health") ||
                        p.startsWith("/.well-known/") ||
                        p.startsWith("/statuslist/") ||
                        // OID4VCI public surface
                        p.startsWith("/credential-offer/") ||
                        p == "/token" ||
                        p == "/credential" ||
                        // Swagger UI + OpenAPI spec
                        p == "/v3/api-docs" ||
                        p.startsWith("/v3/api-docs/") ||
                        p == "/swagger-ui" ||
                        p.startsWith("/swagger-ui/") ||
                        p == "/swagger-ui.html"
                }.permitAll()

                // Dev shortcuts: open when the dev-shortcut profile is active.
                if (devShortcut) {
                    auth.requestMatchers { it.requestURI.startsWith("/dev/") }.permitAll()
                } else {
                    auth.requestMatchers { it.requestURI.startsWith("/dev/") }.denyAll()
                }

                // Privileged surfaces: HTTP Basic against the admin user.
                auth.requestMatchers { req ->
                    req.requestURI.startsWith("/integrity/") || req.requestURI.startsWith("/admin/")
                }.authenticated()
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
