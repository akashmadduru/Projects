package com.acme.ecommerce.inventory.domain;

import java.util.UUID;

/** Fixed ids of the platform seller and its default warehouse. */
public final class Platform {

  public static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-00000000a11e");
  public static final String DEFAULT_WAREHOUSE_CODE = "WH-BLR";
  public static final UUID DEFAULT_WAREHOUSE_ID =
      UUID.fromString("eeeeeeee-0000-0000-0000-0000000000b1");

  private Platform() {}
}
