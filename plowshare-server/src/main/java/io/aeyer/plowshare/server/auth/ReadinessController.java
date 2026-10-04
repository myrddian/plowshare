package io.aeyer.plowshare.server.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A status-only operational HTTP probe; no account credentials or application data. */
@RestController
public class ReadinessController {
  private final JdbcTemplate jdbc;
  private volatile boolean ready;

  public ReadinessController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @EventListener
  public void ready(ApplicationReadyEvent event) {
    ready = true;
  }

  @GetMapping("/ready")
  public ResponseEntity<Void> probe() {
    if (!ready) return ResponseEntity.status(503).build();
    try {
      if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)))
        return ResponseEntity.status(503).build();
      return ResponseEntity.noContent().build();
    } catch (org.springframework.dao.DataAccessException unavailable) {
      return ResponseEntity.status(503).build();
    }
  }
}
