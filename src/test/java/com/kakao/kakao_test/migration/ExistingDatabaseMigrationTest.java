package com.kakao.kakao_test.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

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

            var upgrade = Flyway.configure().dataSource(url, "sa", "").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();

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
