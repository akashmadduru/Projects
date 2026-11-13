package com.acme.ecommerce.support.ticket;

import com.acme.ecommerce.support.Paged;
import com.acme.ecommerce.support.ticket.TicketService.AssignRequest;
import com.acme.ecommerce.support.ticket.TicketService.CreateRequest;
import com.acme.ecommerce.support.ticket.TicketService.CsatRequest;
import com.acme.ecommerce.support.ticket.TicketService.MessageRequest;
import com.acme.ecommerce.support.ticket.TicketService.StatusRequest;
import com.acme.ecommerce.support.ticket.TicketService.Ticket;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer ticket API (JWT, owner-scoped) and the agent queue (SUPPORT or ADMIN). */
@RestController
public class TicketController {

  private static final String AGENT = "hasAnyRole('SUPPORT','ADMIN')";

  private final TicketService tickets;

  public TicketController(TicketService tickets) {
    this.tickets = tickets;
  }

  private static UUID uid(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  // ---- customer
  @PostMapping("/api/support/tickets")
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest request) {
    return tickets.create(uid(jwt), jwt.getClaimAsString("email"), request);
  }

  @GetMapping("/api/support/tickets")
  public Paged<Ticket> mine(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return tickets.listMine(uid(jwt), status, page, size);
  }

  @GetMapping("/api/support/tickets/{id}")
  public Ticket one(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return tickets.getMine(uid(jwt), id);
  }

  @PostMapping("/api/support/tickets/{id}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket reply(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody MessageRequest request) {
    return tickets.customerMessage(uid(jwt), id, request.body());
  }

  @PostMapping("/api/support/tickets/{id}/close")
  public Ticket close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return tickets.close(uid(jwt), id);
  }

  @PostMapping("/api/support/tickets/{id}/csat")
  public Ticket csat(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody CsatRequest request) {
    return tickets.csat(uid(jwt), id, request.score());
  }

  // ---- agent
  @GetMapping("/api/admin/support/tickets")
  @PreAuthorize(AGENT)
  public Paged<Ticket> queue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String assignee,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return tickets.queue(status, priority, assignee, q, uid(jwt), page, size);
  }

  @GetMapping("/api/admin/support/tickets/{id}")
  @PreAuthorize(AGENT)
  public Ticket agentOne(@PathVariable UUID id) {
    return tickets.agentGet(id);
  }

  @PostMapping("/api/admin/support/tickets/{id}/assign")
  @PreAuthorize(AGENT)
  public Ticket assign(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody AssignRequest request) {
    return tickets.assign(
        id, Boolean.TRUE.equals(request.me()) ? "me" : request.userId(), uid(jwt));
  }

  @PostMapping("/api/admin/support/tickets/{id}/messages")
  @PreAuthorize(AGENT)
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket agentReply(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody MessageRequest request) {
    return tickets.agentMessage(id, uid(jwt), request.body(), Boolean.TRUE.equals(request.internal()));
  }

  @PostMapping("/api/admin/support/tickets/{id}/status")
  @PreAuthorize(AGENT)
  public Ticket status(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody StatusRequest request) {
    return tickets.setStatus(id, uid(jwt), request);
  }
}
