package armchair.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class CustomErrorController implements ErrorController {

    private static final Logger logger = LoggerFactory.getLogger(CustomErrorController.class);

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        Throwable exception = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

        int statusCode = status instanceof Integer ? (Integer) status : 0;

        if (statusCode >= 500) {
            if (exception != null) {
                logger.error("Error serving request [{}] (status {}): {}",
                        uri, status, message, exception);
            } else {
                logger.error("Error serving request [{}] (status {}): {}",
                        uri, status, message);
            }
        } else {
            logger.warn("Error serving request [{}] (status {}): {}",
                    uri, status, message);
        }

        return "error";
    }
}
