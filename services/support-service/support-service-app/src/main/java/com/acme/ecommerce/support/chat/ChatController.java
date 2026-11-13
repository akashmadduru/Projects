package com.acme.ecommerce.support.chat;

import com.acme.ecommerce.support.chat.ChatService.ChatRequest;
import com.acme.ecommerce.support.chat.ChatService.ChatResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Chat endpoint: anonymous allowed; a valid bearer token personalises the answers. */
@RestController
public class ChatController {

  private final ChatService chat;

  public ChatController(ChatService chat) {
    this.chat = chat;
  }

  @PostMapping("/api/support/chat")
  public ChatResponse chat(@AuthenticationPrincipal Jwt jwt, @RequestBody ChatRequest request) {
    return chat.chat(jwt == null ? null : UUID.fromString(jwt.getSubject()), request);
  }
}
