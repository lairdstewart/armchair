package armchair.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@Profile("dev")
public class DevSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new MockAuthFilter(), OAuth2AuthorizationRequestRedirectFilter.class)
            .formLogin(form -> form.disable())
            .logout(logout -> logout
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .clearAuthentication(true)
                .permitAll()
            );

        return http.build();
    }

    static class MockAuthFilter extends OncePerRequestFilter {

        private final HttpSessionSecurityContextRepository sessionRepo =
            new HttpSessionSecurityContextRepository();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String path = request.getRequestURI();

            if ("/oauth2/authorization/google".equals(path)) {
                mockLogin(request, response,
                    Map.of("sub", "dev-user-subject", "name", "Dev User"),
                    "sub", "google");
                response.sendRedirect(consumePostLoginRedirect(request));
                return;
            }

            if ("/oauth2/authorization/github".equals(path)) {
                mockLogin(request, response,
                    Map.of("id", 12345, "login", "dev-github", "name", "Dev GitHub User"),
                    "login", "github");
                response.sendRedirect(consumePostLoginRedirect(request));
                return;
            }

            filterChain.doFilter(request, response);
        }

        private String consumePostLoginRedirect(HttpServletRequest request) {
            Object redirect = request.getSession().getAttribute("POST_LOGIN_REDIRECT");
            if (redirect instanceof String url && url.startsWith("/") && !url.contains("://") && !url.contains("//")) {
                request.getSession().removeAttribute("POST_LOGIN_REDIRECT");
                return url;
            }
            return "/my-profile";
        }

        private void mockLogin(HttpServletRequest request, HttpServletResponse response,
                               Map<String, Object> attributes, String nameKey, String provider) {
            OAuth2User oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                nameKey
            );
            OAuth2AuthenticationToken authToken = new OAuth2AuthenticationToken(
                oauth2User, oauth2User.getAuthorities(), provider
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authToken);
            SecurityContextHolder.setContext(context);
            sessionRepo.saveContext(context, request, response);
        }
    }
}
