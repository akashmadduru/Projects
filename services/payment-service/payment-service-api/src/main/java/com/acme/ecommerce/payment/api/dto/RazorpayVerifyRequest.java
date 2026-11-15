package com.acme.ecommerce.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The three values Razorpay Checkout hands the browser after a successful payment. */
public record RazorpayVerifyRequest(
    @NotBlank @Size(max = 100) String razorpayOrderId,
    @NotBlank @Size(max = 100) String razorpayPaymentId,
    @NotBlank @Size(max = 200) String razorpaySignature) {}
