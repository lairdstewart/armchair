package armchair.controller;

import armchair.service.ImportExportService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ImportExportController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ImportExportController.class);

    @Autowired
    private ImportExportService importExportService;

    @GetMapping("/export-csv")
    public ResponseEntity<String> exportCsv(HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        String csv = importExportService.generateCsv(userId);
        String filename = "books.csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", filename);

        return ResponseEntity.ok()
            .headers(headers)
            .body(csv);
    }

    @GetMapping("/import-goodreads")
    public String showImportGoodreads(Model model, HttpSession session,
                                       @RequestParam(required = false) Integer imported,
                                       @RequestParam(required = false) Integer skipped,
                                       @RequestParam(required = false) Integer failed) {
        addNavigationAttributes(model, "profile");
        if (imported != null) {
            String message = "Successfully imported " + imported + " books";
            if (skipped != null && skipped > 0) {
                message += ", " + skipped + " were already in library";
            }
            if (failed != null && failed > 0) {
                message += ", failed to import " + failed;
            }
            model.addAttribute("resultMessage", message);
        }
        return "import-goodreads";
    }

    @PostMapping("/import-goodreads")
    public String importGoodreads(@RequestParam("file") MultipartFile file, HttpSession session) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return "redirect:/login";
        }

        try {
            ImportExportService.ImportResult result = importExportService.importGoodreads(file.getInputStream(), userId);
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        } catch (java.io.IOException e) {
            log.error("Error reading uploaded file: {}", e.getMessage());
            return "redirect:/my-books?selectedBookshelf=UNRANKED";
        }
    }
}
