package ai.reviewforge.persistence;

import ai.reviewforge.ReviewForgeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ReviewForgeApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("reviewforge")
            .withUsername("reviewforge")
            .withPassword("reviewforge");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("reviewforge.jobs.enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsAllCoreTables() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'app_users', 'github_installations', 'repositories', 'pull_requests',
                    'analysis_jobs', 'findings', 'generated_tests', 'test_runs', 'rejected_findings'
                  )
                """, Integer.class);

        assertThat(count).isEqualTo(9);
    }

    @Test
    void addsWorkflowColumnsForQueueingAndModelAccounting() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (
                    (table_name = 'analysis_jobs' AND column_name IN
                        ('attempts', 'lease_expires_at', 'context_file_count', 'input_tokens', 'output_tokens'))
                    OR (table_name = 'generated_tests' AND column_name IN
                        ('analysis_job_id', 'head_sha', 'file_content', 'attempts', 'lease_expires_at'))
                    OR (table_name = 'test_runs' AND column_name IN
                        ('attempts', 'lease_expires_at', 'duration_millis'))
                  )
                """, Integer.class);

        assertThat(count).isEqualTo(13);
    }

    @Test
    void allowsOnlyOneActiveAnalysisPerPullRequest() {
        String indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'uq_analysis_active_per_pull_request'
                """, String.class);

        assertThat(indexDefinition).contains("UNIQUE").contains("pull_request_id");
    }

    @Test
    void preventsDuplicateActiveGenerationAndRunWork() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                    'uq_generated_test_active_per_finding',
                    'uq_test_run_active_per_generated_test'
                  )
                """, Integer.class);

        assertThat(count).isEqualTo(2);
    }
}
