package com.kakao.kakao_test.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExistingDatabaseMigrationTest {
    @Test
    void 기존DB는_V1을_기준으로_후속_버전을_적용하고_재시작시_로그를_보존한다() throws Exception {
        String url = "jdbc:h2:mem:legacy-migration;MODE=MySQL";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__initial_schema.sql"));
            statement.executeUpdate("INSERT INTO target_server (id, server_name, mcp_token) VALUES (1, 'demo', 'fixture')");
            statement.executeUpdate("INSERT INTO server_log (server_id, event_id, message) VALUES (1, 'old', 'before migration')");

            var firstStart = Flyway.configure().dataSource(url, "sa", "").baselineOnMigrate(true).target("3").load();
            assertThat(firstStart.migrate().migrationsExecuted).isEqualTo(2);
            statement.executeUpdate("INSERT INTO log_alert_channel (server_id, webhook_hash, encrypted_webhook) VALUES (1, 'hash', 'fixture')");
            statement.executeUpdate("INSERT INTO log_alert_delivery (id, server_id, sent_at) VALUES ('old-delivery', 1, 1000)");

            var throughV5 = Flyway.configure().dataSource(url, "sa", "").target("5").load();
            assertThat(throughV5.migrate().migrationsExecuted).isEqualTo(2);
            statement.executeUpdate("""
                    INSERT INTO incident (id, server_id, error_group_key, representative_log_id,
                                          window_start, window_end, expires_at, created_at, updated_at)
                    SELECT 'old-delivery', server_id, 'old-group', id,
                           '2026-11-28 10:00:00', '2026-11-28 10:05:00', 86401000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    FROM server_log WHERE server_id = 1 AND event_id = 'old'
                    """);

            var upgrade = Flyway.configure().dataSource(url, "sa", "").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();

            try (var rows = statement.executeQuery("""
                    SELECT delivery_scoped, live_window, error_group_key, window_start, window_end, expires_at
                    FROM incident WHERE id = 'old-delivery'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("delivery_scoped", Boolean.class)).isFalse();
                assertThat(rows.getObject("live_window", Boolean.class)).isFalse();
                assertThat(rows.getString("error_group_key")).isEqualTo("old-group");
                assertThat(rows.getObject("window_start", LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 11, 28, 10, 0));
                assertThat(rows.getObject("window_end", LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 11, 28, 10, 5));
                assertThat(rows.getLong("expires_at")).isEqualTo(86_401_000L);
            }

            try (var rows = statement.executeQuery("""
                    SELECT c.created_at, c.updated_at, d.created_at, d.updated_at, d.sent_at
                    FROM log_alert_channel c JOIN log_alert_delivery d ON d.server_id = c.server_id
                    """)) {
                assertThat(rows.next()).isTrue();
                for (int column = 1; column <= 4; column++) {
                    assertThat(rows.getTimestamp(column)).isNotNull();
                }
                assertThat(rows.getLong("sent_at")).isEqualTo(1000);
            }

            try (var rows = statement.executeQuery("SELECT message, error_group_key, alert_delivery FROM server_log WHERE event_id = 'old'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("message")).isEqualTo("before migration");
                assertThat(rows.getString("error_group_key")).isNull();
                assertThat(rows.getString("alert_delivery")).isNull();
            }
        }
    }
}
