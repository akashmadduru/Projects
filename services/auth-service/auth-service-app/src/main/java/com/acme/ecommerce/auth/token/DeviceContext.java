package com.acme.ecommerce.auth.token;

/**
 * The client-side context a refresh token is minted for.
 *
 * <p>Recorded for forensics, NOT for authorization. Nothing in the rotation logic compares the
 * stored user-agent or IP against the presenting client, and it deliberately must not: mobile
 * networks change source address mid-session and browsers change user-agent on every update, so
 * binding a session to either produces constant spurious logouts while stopping an attacker for
 * about as long as it takes to copy a header.
 *
 * <p>What it IS for: when {@code auth.refresh.reuse_detected} fires, these two fields are what turn
 * "someone replayed a token" into an actionable incident.
 *
 * @param userAgent raw {@code User-Agent} header, truncated by {@link #truncated()} before storage
 * @param ipAddress source address as seen by this service
 */
public record DeviceContext(String userAgent, String ipAddress) {

  /** Matches {@code refresh_tokens.user_agent}'s column width. */
  public static final int MAX_USER_AGENT_LENGTH = 400;

  /** Context for a call with no HTTP request behind it (tests, scheduled work). */
  public static DeviceContext unknown() {
    return new DeviceContext(null, null);
  }

  /**
   * Returns a copy safe to persist.
   *
   * <p>A hostile client can send a multi-kilobyte {@code User-Agent}; without this the insert fails
   * with a value-too-long error and the login is rejected for a reason that has nothing to do with
   * the credentials.
   */
  public DeviceContext truncated() {
    if (userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH) {
      return this;
    }
    return new DeviceContext(userAgent.substring(0, MAX_USER_AGENT_LENGTH), ipAddress);
  }
}
