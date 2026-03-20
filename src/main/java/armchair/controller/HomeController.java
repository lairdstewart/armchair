package armchair.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController extends BaseController {

    @GetMapping("/")
    public String showWelcome(Model model) {
        addNavigationAttributes(model, "about");
        return "welcome";
    }

    @GetMapping("/login")
    public String showLogin(Model model) {
        addNavigationAttributes(model, "login");
        return "login";
    }
}
