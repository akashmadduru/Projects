package com.acme.ecommerce.user.web;

import com.acme.ecommerce.user.privacy.PrivacyService;
import com.acme.ecommerce.user.privacy.PrivacyService.DeletionState;
import com.acme.ecommerce.user.privacy.PrivacyService.ExportJob;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer privacy self-service: data export and account deletion (JWT). */
@RestController
public class PrivacyController {

  public record DeleteRequest(String password, String otp) {}

  public record DeletionResponse(boolean scheduled, java.time.OffsetDateTime scheduledFor) {
    static DeletionResponse of(DeletionState s) {
      return new DeletionResponse(s.scheduled(), s.scheduledFor());
    }
  }

  private final PrivacyService privacy;

  public PrivacyController(PrivacyService privacy) {
    this.privacy = privacy;
  }

  @PostMapping("/api/users/me/export")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ExportJob requestExport(@AuthenticationPrincipal Jwt jwt) {
    return privacy.requestExport(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"));
  }

  @GetMapping("/api/users/me/export/{jobId}")
  public ExportJob export(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
    return privacy.getExport(UUID.fromString(jwt.getSubject()), jobId);
  }

  @GetMapping("/api/users/me/export/{jobId}/download")
  public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
    byte[] zip = privacy.download(UUID.fromString(jwt.getSubject()), jobId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-data-export.zip\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(zip);
  }

  @GetMapping("/api/users/me/delete")
  public DeletionResponse deletion(@AuthenticationPrincipal Jwt jwt) {
    return DeletionResponse.of(privacy.deletionState(UUID.fromString(jwt.getSubject())));
  }

  /** Emails a confirmation code (only for accounts without a password). */
  @PostMapping("/api/users/me/delete/code")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sendCode(@AuthenticationPrincipal Jwt jwt) {
    privacy.sendDeleteCode(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"));
  }

  @PostMapping("/api/users/me/delete")
  public DeletionResponse delete(
      @AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) DeleteRequest body) {
    DeleteRequest b = body == null ? new DeleteRequest(null, null) : body;
    return DeletionResponse.of(
        privacy.scheduleDeletion(
            UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"), b.password(), b.otp()));
  }

  @PostMapping("/api/users/me/delete/cancel")
  public DeletionResponse cancel(@AuthenticationPrincipal Jwt jwt) {
    return DeletionResponse.of(privacy.cancelDeletion(UUID.fromString(jwt.getSubject())));
  }

  /** Dev trigger (internal token): run every deletion due at {@code now} (default: current time). */
  @PostMapping("/internal/privacy/deletions/run-due")
  public java.util.Map<String, Integer> runDue(@RequestParam(required = false) String now) {
    java.time.Instant at = now == null || now.isBlank() ? java.time.Instant.now() : java.time.Instant.parse(now);
    return java.util.Map.of("completed", privacy.runDue(at));
  }
}
