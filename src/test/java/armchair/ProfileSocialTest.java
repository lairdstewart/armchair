package armchair;

import armchair.entity.Book;
import armchair.entity.BookCategory;
import armchair.entity.Bookshelf;
import armchair.entity.Follow;
import armchair.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileSocialTest extends BaseIntegrationTest {

    // --- GET /user/{username} ---

    @Test
    void viewPublicProfile() throws Exception {
        User target = createOAuthUser("publicuser", "oauth-public-1");
        target.setPublishLists(true);
        userRepository.save(target);

        Book dune = createVerifiedBook("OL100W", "Dune", "Frank Herbert");
        addRanking(target.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(get("/user/publicuser")
                        .with(oauthUser("oauth-viewer-1")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("viewUsername", "publicuser"));
    }

    @Test
    void viewNonexistentUserRedirects() throws Exception {
        createOAuthUser("viewer1", "oauth-viewer-2");

        mockMvc.perform(get("/user/nosuchuser")
                        .with(oauthUser("oauth-viewer-2")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void viewPrivateProfileRedirects() throws Exception {
        User privateUser = createOAuthUser("privateuser", "oauth-private-1");
        // publishLists defaults to false

        createOAuthUser("viewer2", "oauth-viewer-3");

        mockMvc.perform(get("/user/privateuser")
                        .with(oauthUser("oauth-viewer-3")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void viewCuratedProfileAlwaysVisible() throws Exception {
        User curated = createOAuthUser("curatedlist", "oauth-curated-1");
        curated.setCurated(true);
        // publishLists is false, but curated lists are always visible
        userRepository.save(curated);

        mockMvc.perform(get("/user/curatedlist")
                        .with(oauthUser("oauth-viewer-4")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("viewUsername", "curatedlist"));
    }

    // --- POST /follow ---

    @Test
    void followUser() throws Exception {
        User follower = createOAuthUser("follower1", "oauth-follower-1");
        User target = createOAuthUser("target1", "oauth-target-1");

        mockMvc.perform(post("/follow")
                        .param("userId", String.valueOf(target.getId()))
                        .with(oauthUser("oauth-follower-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(followRepository.existsByFollowerIdAndFollowedId(follower.getId(), target.getId())).isTrue();
    }

    @Test
    void followYourselfIsIgnored() throws Exception {
        User user = createOAuthUser("selffollow", "oauth-self-1");

        mockMvc.perform(post("/follow")
                        .param("userId", String.valueOf(user.getId()))
                        .with(oauthUser("oauth-self-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(followRepository.existsByFollowerIdAndFollowedId(user.getId(), user.getId())).isFalse();
    }

    @Test
    void followNonexistentUserIsIgnored() throws Exception {
        User follower = createOAuthUser("follower2", "oauth-follower-2");

        mockMvc.perform(post("/follow")
                        .param("userId", "999999")
                        .with(oauthUser("oauth-follower-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(followRepository.findByFollowerId(follower.getId())).isEmpty();
    }

    @Test
    void duplicateFollowIsIdempotent() throws Exception {
        User follower = createOAuthUser("follower3", "oauth-follower-3");
        User target = createOAuthUser("target2", "oauth-target-2");

        // Follow twice
        mockMvc.perform(post("/follow")
                        .param("userId", String.valueOf(target.getId()))
                        .with(oauthUser("oauth-follower-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/follow")
                        .param("userId", String.valueOf(target.getId()))
                        .with(oauthUser("oauth-follower-3")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(followRepository.findByFollowerId(follower.getId())).hasSize(1);
    }

    // --- POST /unfollow ---

    @Test
    void unfollowUser() throws Exception {
        User follower = createOAuthUser("unfollower1", "oauth-unfollower-1");
        User target = createOAuthUser("target3", "oauth-target-3");
        followRepository.save(new Follow(follower, target));

        mockMvc.perform(post("/unfollow")
                        .param("userId", String.valueOf(target.getId()))
                        .with(oauthUser("oauth-unfollower-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(followRepository.existsByFollowerIdAndFollowedId(follower.getId(), target.getId())).isFalse();
    }

    @Test
    void unfollowWhenNotFollowingIsNoOp() throws Exception {
        User user = createOAuthUser("unfollower2", "oauth-unfollower-2");
        User target = createOAuthUser("target4", "oauth-target-4");

        mockMvc.perform(post("/unfollow")
                        .param("userId", String.valueOf(target.getId()))
                        .with(oauthUser("oauth-unfollower-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(followRepository.findByFollowerId(user.getId())).isEmpty();
    }

    // --- POST /toggle-publish-lists ---

    @Test
    void togglePublishListsOn() throws Exception {
        User user = createOAuthUser("toggler1", "oauth-toggler-1");
        assertThat(user.isPublishLists()).isFalse();

        mockMvc.perform(post("/toggle-publish-lists")
                        .with(oauthUser("oauth-toggler-1")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-profile"));

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.isPublishLists()).isTrue();
    }

    @Test
    void togglePublishListsOff() throws Exception {
        User user = createOAuthUser("toggler2", "oauth-toggler-2");
        user.setPublishLists(true);
        userRepository.save(user);

        mockMvc.perform(post("/toggle-publish-lists")
                        .with(oauthUser("oauth-toggler-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.isPublishLists()).isFalse();
    }

    // --- POST /change-username ---

    @Test
    void changeUsername() throws Exception {
        createOAuthUser("oldname", "oauth-chg-1");

        mockMvc.perform(post("/change-username")
                        .param("username", "newname")
                        .with(oauthUser("oauth-chg-1")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-profile"));

        assertThat(userRepository.findByUsername("newname")).isPresent();
        assertThat(userRepository.findByUsername("oldname")).isEmpty();
    }

    @Test
    void changeUsernameToSameNameRedirects() throws Exception {
        createOAuthUser("samename", "oauth-chg-2");

        mockMvc.perform(post("/change-username")
                        .param("username", "samename")
                        .with(oauthUser("oauth-chg-2")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-profile"));
    }

    @Test
    void changeUsernameEmptyShowsError() throws Exception {
        createOAuthUser("emptytest", "oauth-chg-3");

        mockMvc.perform(post("/change-username")
                        .param("username", "")
                        .with(oauthUser("oauth-chg-3")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
    }

    @Test
    void changeUsernameInvalidCharsShowsError() throws Exception {
        createOAuthUser("invalidtest", "oauth-chg-4");

        mockMvc.perform(post("/change-username")
                        .param("username", "bad name!")
                        .with(oauthUser("oauth-chg-4")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
    }

    @Test
    void changeUsernameTakenShowsError() throws Exception {
        createOAuthUser("existing", "oauth-chg-5");
        createOAuthUser("wantsname", "oauth-chg-6");

        mockMvc.perform(post("/change-username")
                        .param("username", "existing")
                        .with(oauthUser("oauth-chg-6")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
    }

    // --- POST /delete-profile ---

    @Test
    void deleteProfile() throws Exception {
        User user = createOAuthUser("deleteme", "oauth-del-1");
        Book dune = createVerifiedBook("OL_DEL_W", "Dune", "Frank Herbert");
        addRanking(user.getId(), dune, Bookshelf.FICTION, BookCategory.LIKED, 0);

        mockMvc.perform(post("/delete-profile")
                        .with(oauthUser("oauth-del-1")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(rankingRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void deleteProfileCleansUpFollows() throws Exception {
        User user = createOAuthUser("delfollow", "oauth-del-2");
        User other = createOAuthUser("other", "oauth-other-1");
        followRepository.save(new Follow(user, other));
        followRepository.save(new Follow(other, user));

        mockMvc.perform(post("/delete-profile")
                        .with(oauthUser("oauth-del-2")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(followRepository.findByFollowerId(user.getId())).isEmpty();
        assertThat(followRepository.findByFollowedId(user.getId())).isEmpty();
    }

    // --- GET /search-profiles ---

    @Test
    void searchProfilesWithQuery() throws Exception {
        User user = createOAuthUser("searchable", "oauth-search-1");
        user.setPublishLists(true);
        userRepository.save(user);

        mockMvc.perform(get("/search-profiles")
                        .param("query", "searchable")
                        .with(oauthUser("oauth-searcher-1")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("searchResults"));
    }

    @Test
    void searchProfilesWithoutQuery() throws Exception {
        mockMvc.perform(get("/search-profiles")
                        .with(oauthUser("oauth-searcher-2")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("recentProfiles"));
    }

    @Test
    void searchProfilesExcludesPrivateUsers() throws Exception {
        User privateUser = createOAuthUser("hiddenuser", "oauth-search-2");
        // publishLists defaults to false

        mockMvc.perform(get("/search-profiles")
                        .param("query", "hiddenuser")
                        .with(oauthUser("oauth-searcher-3")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("searchResults", org.hamcrest.Matchers.empty()));
    }

    @Test
    void searchProfilesExcludesCuratedLists() throws Exception {
        User curated = createOAuthUser("curatedsearch", "oauth-search-3");
        curated.setCurated(true);
        curated.setPublishLists(true);
        userRepository.save(curated);

        mockMvc.perform(get("/search-profiles")
                        .param("query", "curatedsearch")
                        .with(oauthUser("oauth-searcher-4")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("searchResults", org.hamcrest.Matchers.empty()));
    }
}
