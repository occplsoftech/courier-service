package com.courier.shared.config;

import lombok.extern.slf4j.Slf4j;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migration behaviour.
 *
 * <p>Most settings live in {@code application.yml}; this class exists for the
 * migration strategy and to log what actually ran, which is the first thing anyone
 * asks after a bad deploy.
 *
 * <p><b>Rules</b> (also in {@code MEMORY/ARCHITECTURE.md} §8):
 * <ul>
 *   <li>Forward-only. A merged migration is never edited — its checksum is recorded,
 *       and changing it makes every existing environment fail validation.</li>
 *   <li>Hibernate runs with {@code ddl-auto: validate}; the schema is Flyway's alone.</li>
 *   <li>In production, migration runs as a separate step before the new version
 *       starts, so a failed migration does not leave a half-started app.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            repairFailed(flyway);
            logPending(flyway);
            var result = flyway.migrate();
            log.info("Flyway applied {} migration(s); schema now at version {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        };
    }

    /**
     * MySQL cannot roll back DDL, so Flyway leaves a failed migration in the history and
     * refuses to start until it is repaired. A failed migration changed nothing that is
     * recorded as done, so dropping its history row and letting it run again (once it has
     * been fixed) is what a person would do by hand. Only failed rows are touched.
     */
    private void repairFailed(Flyway flyway) {
        boolean failed = Arrays.stream(flyway.info().all()).anyMatch(i -> i.getState().isFailed());
        if (failed) {
            log.warn("Flyway: a previous migration failed; repairing the history before migrating");
            flyway.repair();
        }
    }

    private void logPending(Flyway flyway) {
        var pending = flyway.info().pending();
        if (pending.length == 0) {
            log.info("Flyway: schema is up to date");
            return;
        }
        log.info("Flyway: {} pending migration(s)", pending.length);
        for (var info : pending) {
            log.info("  -> V{} {}", info.getVersion(), info.getDescription());
        }
    }
}
