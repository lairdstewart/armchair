package armchair.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
@Profile("!dev")
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Bean
    public OAuth2AuthorizationRequestResolver customAuthorizationRequestResolver() {
        DefaultOAuth2AuthorizationRequestResolver resolver =
            new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");

        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request);
                return authorizationRequest != null ?
                    customizeAuthorizationRequest(authorizationRequest) : null;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request, clientRegistrationId);
                return authorizationRequest != null ?
                    customizeAuthorizationRequest(authorizationRequest) : null;
            }
        };
    }

    private OAuth2AuthorizationRequest customizeAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest) {
        // Only add prompt=select_account for Google (not applicable to GitHub)
        if (authorizationRequest.getAuthorizationUri().contains("accounts.google.com")) {
            Map<String, Object> additionalParameters = new HashMap<>(authorizationRequest.getAdditionalParameters());
            additionalParameters.put("prompt", "select_account");
            return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(additionalParameters)
                .build();
        }
        return authorizationRequest;
    }

    @Bean
    public AuthenticationSuccessHandler postLoginRedirectHandler() {
        return (request, response, authentication) -> {
            Object redirect = request.getSession().getAttribute("POST_LOGIN_REDIRECT");
            String targetUrl = "/my-profile";
            if (redirect instanceof String url && url.startsWith("/") && !url.contains("://") && !url.contains("//")) {
                targetUrl = url;
                request.getSession().removeAttribute("POST_LOGIN_REDIRECT");
            }
            new SimpleUrlAuthenticationSuccessHandler(targetUrl).onAuthenticationSuccess(request, response, authentication);
        };
    }

    @Bean
    public AccessDeniedHandler csrfAwareAccessDeniedHandler() {
        AccessDeniedHandler defaultHandler = new AccessDeniedHandlerImpl();
        return (request, response, accessDeniedException) -> {
            if (accessDeniedException instanceof InvalidCsrfTokenException
                    || accessDeniedException instanceof MissingCsrfTokenException) {
                response.sendRedirect("/my-books");
            } else {
                defaultHandler.handle(request, response, accessDeniedException);
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/**").permitAll()
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .exceptionHandling(exceptions -> exceptions
                .accessDeniedHandler(csrfAwareAccessDeniedHandler())
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .successHandler(postLoginRedirectHandler())
                .failureHandler((request, response, exception) -> {
                    logger.error("OAuth2 authentication failed: {} - {}",
                        exception.getClass().getSimpleName(), exception.getMessage());
                    response.sendRedirect("/error");
                })
                .authorizationEndpoint(authorization -> authorization
                    .authorizationRequestResolver(customAuthorizationRequestResolver())
                )
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .clearAuthentication(true)
                .permitAll()
            );

        return http.build();
    }
}
