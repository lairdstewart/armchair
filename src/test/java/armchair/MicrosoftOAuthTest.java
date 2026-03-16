package armchair;

import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MicrosoftOAuthTest extends BaseIntegrationTest {

    @Test
    void microsoftUserCanSetUpUsername() throws Exception {
        mockMvc.perform(get("/my-profile").with(oauthUser("ms-subject-123", "microsoft")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/setup-username"));

        mockMvc.perform(post("/setup-username")
                .param("username", "msuser")
                .with(oauthUser("ms-subject-123", "microsoft"))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my-profile"));

        mockMvc.perform(get("/my-profile").with(oauthUser("ms-subject-123", "microsoft")))
            .andExpect(status().isOk());
    }

    @Test
    void microsoftUserIsDistinctFromGoogleAndGitHub() throws Exception {
        createOAuthUser("googleuser", "12345", "google");
        createOAuthUser("ghuser", "12345", "github");
        createOAuthUser("msuser", "12345", "microsoft");

        mockMvc.perform(get("/my-profile").with(oauthUser("12345", "google")))
            .andExpect(status().isOk())
            .andExpect(model().attribute("username", "googleuser"));

        mockMvc.perform(get("/my-profile").with(oauthUser("12345", "github")))
            .andExpect(status().isOk())
            .andExpect(model().attribute("username", "ghuser"));

        mockMvc.perform(get("/my-profile").with(oauthUser("12345", "microsoft")))
            .andExpect(status().isOk())
            .andExpect(model().attribute("username", "msuser"));
    }

    @Test
    void loginPageShowsAllThreeProviders() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/google")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/github")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/microsoft")));
    }
}
