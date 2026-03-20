package armchair.controller;

import armchair.entity.User;
import armchair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.ui.Model;

public abstract class BaseController {

    @Autowired
    protected UserRepository userRepository;

    record OAuthIdentity(String subject, String provider) {}

    protected OAuthIdentity getOAuthIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            OAuth2User oauth2User = oauthToken.getPrincipal();
            String provider = oauthToken.getAuthorizedClientRegistrationId();
            String subject;
            if ("github".equals(provider)) {
                Object id = oauth2User.getAttribute("id");
                subject = id != null ? id.toString() : null;
            } else {
                subject = oauth2User.getAttribute("sub");
            }
            if (subject != null) {
                return new OAuthIdentity(subject, provider);
            }
        }
        return null;
    }

    protected Long getCurrentUserId() {
        OAuthIdentity identity = getOAuthIdentity();
        if (identity == null) return null;
        return userRepository.findByOauthSubjectAndOauthProvider(identity.subject(), identity.provider())
            .map(User::getId).orElse(null);
    }

    protected void addNavigationAttributes(Model model, String currentPage) {
        boolean isLoggedIn = getOAuthIdentity() != null;
        model.addAttribute("isLoggedIn", isLoggedIn);
        model.addAttribute("currentPage", currentPage);
    }

    protected static boolean isSafeRedirectUrl(String url) {
        return url != null && url.startsWith("/") && !url.contains("://") && !url.contains("//");
    }
}
