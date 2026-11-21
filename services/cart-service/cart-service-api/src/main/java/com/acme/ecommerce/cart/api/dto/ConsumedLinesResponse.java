package com.acme.ecommerce.cart.api.dto;

/** How many cart lines a consumed-lines call removed (0 on a repeat). */
public record ConsumedLinesResponse(int removed) {}
