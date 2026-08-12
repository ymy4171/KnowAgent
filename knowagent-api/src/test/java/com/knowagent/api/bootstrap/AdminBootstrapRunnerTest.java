package com.knowagent.api.bootstrap;

import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminBootstrapRunnerTest {

    private static final String VALID_PASSWORD = "CorrectHorseBatteryStaple1";

    @Test
    void disabledBootstrapIsSkippedWithoutTouchingTheService() {
        RecordingAdminBootstrap bootstrap = new RecordingAdminBootstrap();
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                properties(false, "acme", null, "admin@acme.test", null, VALID_PASSWORD), bootstrap);

        runner.run(null);

        assertThat(bootstrap.calls).isZero();
    }

    @Test
    void enabledBootstrapRejectsMissingPassword() {
        RecordingAdminBootstrap bootstrap = new RecordingAdminBootstrap();
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                properties(true, "acme", null, "admin@acme.test", null, null), bootstrap);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin password");

        assertThat(bootstrap.calls).isZero();
    }

    @Test
    void enabledBootstrapRejectsMissingLogin() {
        RecordingAdminBootstrap bootstrap = new RecordingAdminBootstrap();
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                properties(true, "acme", null, null, null, VALID_PASSWORD), bootstrap);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin login");

        assertThat(bootstrap.calls).isZero();
    }

    @Test
    void enabledBootstrapRejectsWeakPasswordWithoutLeakingIt() {
        RecordingAdminBootstrap bootstrap = new RecordingAdminBootstrap();
        String weak = "short";
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                properties(true, "acme", null, "admin@acme.test", null, weak), bootstrap);

        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> runner.run(null));

        assertThat(thrown.getMessage()).contains("at least 12").doesNotContain(weak);
        assertThat(bootstrap.calls).isZero();
    }

    @Test
    void validConfigurationRunsBootstrapAndNormalizesInput() {
        RecordingAdminBootstrap bootstrap = new RecordingAdminBootstrap();
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                properties(true, "  ACME ", " Acme Co ", "  Admin@Acme.Test ", null, VALID_PASSWORD), bootstrap);

        runner.run(null);

        assertThat(bootstrap.calls).isEqualTo(1);
        assertThat(bootstrap.last.tenantSlug()).isEqualTo("acme");
        assertThat(bootstrap.last.adminLogin()).isEqualTo("admin@acme.test");
        assertThat(bootstrap.last.tenantName()).isEqualTo("Acme Co");
    }

    @Test
    void propertiesToStringRedactsPassword() {
        AdminBootstrapProperties props =
                new AdminBootstrapProperties(false, "acme", null, "admin@acme.test", null, VALID_PASSWORD);

        String repr = props.toString();

        assertThat(repr).doesNotContain(VALID_PASSWORD)
                .contains("[REDACTED]")
                .contains("acme")
                .contains("admin@acme.test");
    }

    private static AdminBootstrapProperties properties(
            boolean enabled,
            String slug,
            String name,
            String login,
            String displayName,
            String password) {
        return new AdminBootstrapProperties(enabled, slug, name, login, displayName, password);
    }

    private static final class RecordingAdminBootstrap implements AdminBootstrap {
        private int calls;
        private AdminBootstrapRequest last;

        @Override
        public void initialize(AdminBootstrapRequest request) {
            calls++;
            last = request;
        }
    }
}
