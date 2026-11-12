package com.acme.ecommerce.order.api.dto;

/** Snapshot of the shipping address at placement time. */
public record ShipToResponse(
    String fullName,
    String phone,
    String line1,
    String line2,
    String city,
    String state,
    String postalCode,
    String country) {}
