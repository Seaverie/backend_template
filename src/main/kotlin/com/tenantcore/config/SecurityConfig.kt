package com.tenantcore.config

import com.tenantcore.common.ErrorBody
import com.tenantcore.security.AuthFilter
import com.tenantcore.security.PlatformPrincipal
import com.tenantcore.security.Principal
import com.tenantcore.security.UserPrincipal
import com.tenantcore.common.Unauthorized
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class SecurityConfig(private val props: TcProperties) {

    @Bean
    fun filterChain(http: HttpSecurity, authFilter: AuthFilter): SecurityFilterChain {
        val p = props.apiPrefix
        http
            .csrf { it.disable() } // bearer tokens only; no cookies carry credentials
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .cors { c ->
                val origins = props.corsOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                c.configurationSource(UrlBasedCorsConfigurationSource().apply {
                    if (origins.isNotEmpty()) registerCorsConfiguration("/**", CorsConfiguration().apply {
                        allowedOrigins = origins
                        allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
                        allowedHeaders = listOf("Authorization", "Content-Type", "Idempotency-Key", "X-Request-ID")
                        exposedHeaders = listOf("X-Request-ID", "Retry-After")
                        maxAge = 600
                    })
                })
            }
            .headers { h ->
                h.contentTypeOptions { }
                h.frameOptions { it.deny() }
                h.referrerPolicy { it.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                // The dashboard loads only its own scripts and talks only to this API.
                h.contentSecurityPolicy { it.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: blob:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'") }
            }
            .authorizeHttpRequests { a ->
                a.requestMatchers("/health", "/ready", "/actuator/health/**").permitAll()
                a.requestMatchers(HttpMethod.POST, "$p/auth/login", "$p/auth/refresh", "$p/platform/auth/login",
                    "$p/auth/forgot-password", "$p/auth/reset-password", "$p/auth/accept-invite").permitAll()
                // Public shop, public files and payment-provider webhooks. Each endpoint does its own checks.
                a.requestMatchers("$p/public/**").permitAll()
                a.requestMatchers("$p/platform/**").hasRole("PLATFORM")
                a.requestMatchers("$p/**").hasRole("TENANT")
                // Everything else is the dashboard itself (static files); its data comes from the API above.
                a.requestMatchers(HttpMethod.GET, "/**").permitAll()
                a.anyRequest().denyAll()
            }
            .exceptionHandling { e ->
                e.authenticationEntryPoint { _, res, _ -> ErrorBody.write(res, 401, "unauthorized", "Authentication required.") }
                e.accessDeniedHandler { _, res, _ -> ErrorBody.write(res, 403, "forbidden", "You do not have permission to do this.") }
            }
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    /** The filter runs inside the security chain only, not a second time as a plain servlet filter. */
    @Bean
    fun authFilterRegistration(f: AuthFilter) = FilterRegistrationBean(f).apply { isEnabled = false }
}

/** The authenticated caller of the current request. */
object Caller {
    fun principal(): Principal = SecurityContextHolder.getContext().authentication?.principal as? Principal ?: throw Unauthorized()
    fun user(): UserPrincipal = principal() as? UserPrincipal ?: throw Unauthorized()
    fun platform(): PlatformPrincipal = principal() as? PlatformPrincipal ?: throw Unauthorized()
}
