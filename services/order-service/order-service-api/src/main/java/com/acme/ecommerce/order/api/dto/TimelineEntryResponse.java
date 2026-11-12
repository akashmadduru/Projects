package com.acme.ecommerce.order.api.dto;

import java.time.Instant;

public record TimelineEntryResponse(String status, String note, String actor, Instant at) {}
