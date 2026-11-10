package com.acme.ecommerce.common.core.error;

import java.util.Map;

/**
 * Thrown when a request is syntactically valid but violates a business invariant - e.g. discounting
 * below cost, or transitioning an order out of a terminal state.
 *
 * <p>Maps to 422, distinct from the 400 used for syntactic/bean-validation failures. That
 * distinction is what lets a client tell "you sent it wrong" apart from "we won't allow that".
 */
public class DomainRuleViolationException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public DomainRuleViolationException(String message) {
    super(ErrorCode.DOMAIN_RULE_VIOLATION, message);
  }

  public DomainRuleViolationException(String message, String rule) {
    super(ErrorCode.DOMAIN_RULE_VIOLATION, message, detailsWith("rule", rule), null);
  }

  public DomainRuleViolationException(String message, Map<String, Object> details) {
    super(ErrorCode.DOMAIN_RULE_VIOLATION, message, details, null);
  }
}
