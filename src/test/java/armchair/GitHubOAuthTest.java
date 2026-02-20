package armchair;

import armchair.entity.User;
import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GitHubOAuthTest extends BaseIntegrationTest {

    @Test
    void githubUserCanSetUpUsername() throws Exception {
        // GitHub user authenticates and visits profile — should redirect to setup
        mockMvc.perform(get("/my-profile").with(oauthUser("12345", "github")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/setup-username"));

        // Submit username
        mockMvc.perform(post("/setup-username")
                .param("username", "ghuser")
                .with(oauthUser("12345", "github"))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my-profile"));

        // Now profile should load
        mockMvc.perform(get("/my-profile").with(oauthUser("12345", "github")))
            .andExpect(status().isOk());
    }

    @Test
    void googleAndGithubUsersWithSameIdAreDistinct() throws Exception {
        // Same numeric ID "12345" from both providers should be different users
        createOAuthUser("googleuser", "12345", "google");
        createOAuthUser("ghuser", "12345", "github");

        // Google user sees their own profile
        mockMvc.perform(get("/my-profile").with(oauthUser("12345", "google")))
            .andExpect(status().isOk())
            .andExpect(model().attribute("username", "googleuser"));

        // GitHub user sees their own profile
        mockMvc.perform(get("/my-profile").with(oauthUser("12345", "github")))
            .andExpect(status().isOk())
            .andExpect(model().attribute("username", "ghuser"));
    }

    @Test
    void loginPageShowsBothProviders() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/google")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/github")));
    }
}
