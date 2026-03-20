package armchair.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("!test")
public class SchemaDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(SchemaDiagnostics.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaDiagnostics(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logSchemaConstraints() {
        try {
            List<Map<String, Object>> constraints = jdbcTemplate.queryForList(
                    "SELECT tc.constraint_name, tc.constraint_type, ccu.column_name " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.constraint_column_usage ccu " +
                    "  ON tc.constraint_name = ccu.constraint_name " +
                    "  AND tc.table_schema = ccu.table_schema " +
                    "WHERE tc.table_name = 'users' " +
                    "ORDER BY tc.constraint_type, tc.constraint_name");

            log.info("Schema diagnostics for 'users' table ({} constraints):", constraints.size());
            for (Map<String, Object> row : constraints) {
                log.info("  constraint: {} type: {} column: {}",
                        row.get("constraint_name"),
                        row.get("constraint_type"),
                        row.get("column_name"));
            }
        } catch (Exception e) {
            log.warn("Failed to query schema diagnostics: {}", e.getMessage());
        }
    }
}
