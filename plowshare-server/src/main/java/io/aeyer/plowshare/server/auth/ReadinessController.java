package io.aeyer.plowshare.server.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A status-only operational HTTP probe; no account credentials or application data. */
@RestController
public class ReadinessController {
  private final DatabaseReadiness database;
  private volatile boolean ready;

  public ReadinessController(DatabaseReadiness database) {
    this.database = database;
  }

  @EventListener
  public void ready(ApplicationReadyEvent event) {
    ready = true;
  }

  @GetMapping("/ready")
  public ResponseEntity<Void> probe() {
    if (!ready) return ResponseEntity.status(503).build();
    return database.available()
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(503).build();
  }
}
