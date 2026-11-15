# 18. Online payment with Stripe or Razorpay

Last checked against the code: 2026-11-15. Razorpay's side is drawn from its public API documentation; it has not
been run against a live account.

The customer chooses a provider at checkout and can switch until they pay. Both end in the same place:
`applyOutcome` marks the payment SUCCEEDED once, and the reporter delivers that to order-service.

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer (browser)
    participant S as payment-service
    participant R as Razorpay
    participant T as Stripe
    participant O as order-service

    C->>S: place order (create payment)
    S->>S: pick provider (requested, else default; Razorpay needs INR)
    alt RAZORPAY
        S->>R: POST /v1/orders (paise, receipt = subject id)
        R-->>S: order_id
    else STRIPE
        S->>T: create PaymentIntent
        T-->>S: client secret
    end
    S-->>C: payment (mode, provider order id or client secret, public key)

    opt customer switches provider before paying
        C->>S: POST /orders/{id}/provider
        S->>R: create order (or Stripe intent)
        S->>T: cancel the old Stripe intent
        S-->>C: payment on the new provider
    end

    C->>R: pay in the Razorpay window (UPI, card, netbanking, wallet)
    R-->>C: order_id, payment_id, signature
    C->>S: POST /payments/{id}/razorpay/verify
    S->>S: HMAC(order_id|payment_id, key secret) matches?
    S->>S: applyOutcome(SUCCEEDED): only moves REQUIRES_PAYMENT, so it runs once
    S->>O: report outcome (retried until delivered)

    R-->>S: webhook payment.captured (signed over raw body)
    S->>S: signature, amount and currency match, event not seen before
    S->>S: applyOutcome again: no-op if already settled
```

Rules that are not obvious from the picture:

- A Razorpay **failed** attempt is not recorded: the customer can retry on the same order. Unpaid orders are
  cancelled by order-service after 30 minutes.
- If money arrives for a payment already settled through the other provider (the customer switched, the abandoned
  attempt still went through), payment-service logs `DOUBLE CHARGE` at ERROR for a manual refund.
- The order page re-checks the provider when opened, so a missed webhook and a closed tab still resolve.

Decision record: [ADR 0005](../adr/0005-multiple-payment-providers.md).
