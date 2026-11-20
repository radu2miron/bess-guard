package edu.tucn.bessguard.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthorizationFilter jwtFilter,
                                                   @Value("${authorization.enabled:true}") boolean enabled)
            throws Exception {

        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (enabled) {
            http.authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(HttpMethod.POST, "/login").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/").permitAll()
                    .anyRequest().authenticated());
            http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        } else {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        }

        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthorizationFilter> disableServletAutoRegistration(JwtAuthorizationFilter filter) {
        FilterRegistrationBean<JwtAuthorizationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
