package com.acme.ecommerce.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.domain.FederatedIdentity;
import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.repository.FederatedIdentityRepository;
import com.acme.ecommerce.auth.repository.RoleRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.service.UserPrincipalService;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.security.Roles;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The link / provision / reject decision, unit-tested.
 *
 * <p>These four branches decide whether a stranger can take over an existing account, so they are
 * tested in isolation with mocked repositories rather than only through an end-to-end flow: an
 * integration test that needs a live Google connection is a test nobody runs, and a branch nobody
 * runs is a branch nobody has checked.
 *
 * <p>{@link OidcIdentity} is a plain record precisely so this file needs no {@code OidcUser} mock,
 * no {@code ClientRegistration} and no network.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FederatedIdentityResolverTest {

  private static final String PROVIDER = "google";
  private static final String SUBJECT = "116123456789012345678";
  private static final String EMAIL = "someone@example.com";

  @Mock private FederatedIdentityRepository identities;
  @Mock private UserAccountRepository users;
  @Mock private RoleRepository roles;
  @Mock private AuthMetrics metrics;

  private FederatedIdentityResolver resolver;

  @BeforeEach
  void setUp() {
    TimeProvider timeProvider = new FixedTimeProvider(Instant.parse("2026-01-01T00:00:00Z"));
    resolver =
        new FederatedIdentityResolver(
            identities, users, roles, new UserPrincipalService(users), timeProvider, metrics);
  }

  private static OidcIdentity identity(boolean emailVerified) {
    return new OidcIdentity(PROVIDER, SUBJECT, EMAIL, emailVerified, "Some One");
  }

  // --- branch 1: existing link -> LOGIN --------------------------------------

  @Test
  @DisplayName("an existing link logs in, matched on the provider subject and NOT on the email")
  void existingLinkLogsIn() throws Exception {
    UserAccount user = userWithRole(UUID.randomUUID(), "linked@example.com");
    FederatedIdentity link =
        FederatedIdentity.link(user.getId(), PROVIDER, SUBJECT, EMAIL, true, Instant.EPOCH);

    when(identities.findByProviderAndProviderUserId(PROVIDER, SUBJECT))
        .thenReturn(Optional.of(link));
    when(users.findWithRolesById(user.getId())).thenReturn(Optional.of(user));

    // NOTE the email mismatch: the IdP now reports a different address than the
    // local account has. The login must still resolve to the SAME local user,
    // because `sub` is the identity and email is not.
    AuthenticatedPrincipal principal = resolver.resolve(identity(true));

    assertThat(principal.userId()).isEqualTo(user.getId());
    assertThat(principal.authMethod()).isEqualTo(AuthMethod.GOOGLE);
    // No new link, no new user.
    verify(identities, never()).saveAndFlush(any());
    verify(users, never()).saveAndFlush(any());
  }

  // --- branch 2: unverified email -> REJECT ----------------------------------

  @Test
  @DisplayName("an unverified IdP email is REJECTED - never linked, never provisioned")
  void unverifiedEmailIsRejected() {
    when(identities.findByProviderAndProviderUserId(PROVIDER, SUBJECT))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolve(identity(false)))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_OIDC_EMAIL_UNVERIFIED);

    // THE account-takeover defence. Anyone able to create an IdP account claiming
    // a victim's address would otherwise inherit the victim's local account.
    verify(users, never()).findWithRolesByEmail(anyString());
    verify(identities, never()).save(any());
    verify(identities, never()).saveAndFlush(any());
    verify(metrics).oauth2Rejected("email_unverified");
  }

  // --- branch 3: verified email matching a local user -> LINK ----------------

  @Test
  @DisplayName("a verified email matching an existing user LINKS, recording the assertion")
  void verifiedEmailLinksToExistingUser() throws Exception {
    UserAccount existing = userWithRole(UUID.randomUUID(), EMAIL);

    when(identities.findByProviderAndProviderUserId(PROVIDER, SUBJECT))
        .thenReturn(Optional.empty());
    when(users.findWithRolesByEmail(EMAIL)).thenReturn(Optional.of(existing));

    AuthenticatedPrincipal principal = resolver.resolve(identity(true));

    assertThat(principal.userId()).isEqualTo(existing.getId());

    ArgumentCaptor<FederatedIdentity> saved = ArgumentCaptor.forClass(FederatedIdentity.class);
    verify(identities).save(saved.capture());
    assertThat(saved.getValue().getUserId()).isEqualTo(existing.getId());
    assertThat(saved.getValue().getProviderUserId()).isEqualTo(SUBJECT);
    // Audit evidence for the one decision that could have allowed a takeover.
    assertThat(saved.getValue().isEmailVerifiedAtLink()).isTrue();
    verify(metrics).oauth2Link();
  }

  // --- branch 4: verified email, no local user -> PROVISION ------------------

  @Test
  @DisplayName("a verified email with no local user PROVISIONS a VIEWER with no password")
  void verifiedEmailProvisionsNewUser() {
    when(identities.findByProviderAndProviderUserId(PROVIDER, SUBJECT))
        .thenReturn(Optional.empty());
    when(users.findWithRolesByEmail(EMAIL)).thenReturn(Optional.empty());
    when(roles.findByName(Roles.ROLE_VIEWER)).thenReturn(Optional.of(role(Roles.ROLE_VIEWER)));
    // Hibernate assigns the id during flush; the mock has to do the same, or the
    // federated-identity insert that follows sees a null user id.
    when(users.saveAndFlush(any(UserAccount.class)))
        .thenAnswer(
            call -> {
              UserAccount saved = call.getArgument(0);
              assignId(saved, UUID.randomUUID());
              return saved;
            });

    AuthenticatedPrincipal principal = resolver.resolve(identity(true));

    ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
    verify(users).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
    // The IdP asserted it, so the local account starts verified.
    assertThat(saved.getValue().isEmailVerified()).isTrue();
    // Never anything above VIEWER, whatever the IdP says.
    assertThat(saved.getValue().getRoles())
        .extracting(RoleEntity::getName)
        .containsExactly(Roles.ROLE_VIEWER);
    assertThat(principal.roles()).containsExactly("VIEWER");

    verify(identities).saveAndFlush(any(FederatedIdentity.class));
    verify(metrics).oauth2Provision();
  }

  @Test
  @DisplayName("a verified identity with no email at all is refused rather than guessed at")
  void missingEmailIsRejected() {
    when(identities.findByProviderAndProviderUserId(PROVIDER, SUBJECT))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> resolver.resolve(new OidcIdentity(PROVIDER, SUBJECT, null, true, "No Email")))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_OIDC_EMAIL_UNVERIFIED);
  }

  @Test
  @DisplayName("a disabled local account cannot be logged into through the IdP")
  void disabledAccountIsRefused() throws Exception {
    UserAccount user = userWithRole(UUID.randomUUID(), EMAIL);
    user.disable();
    FederatedIdentity link =
        FederatedIdentity.link(user.getId(), PROVIDER, SUBJECT, EMAIL, true, Instant.EPOCH);

    when(identities.findByProviderAndProviderUserId(PROVIDER, SUBJECT))
        .thenReturn(Optional.of(link));
    when(users.findWithRolesById(user.getId())).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> resolver.resolve(identity(true)))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
  }

  // --- helpers ---------------------------------------------------------------

  /**
   * Builds a persisted-looking user.
   *
   * <p>The id is normally assigned by Hibernate at flush; reflection is the least-bad way to have a
   * detached entity with a stable id in a unit test, and confining it to this helper keeps it out
   * of the tests themselves.
   */
  private static UserAccount userWithRole(UUID id, String email) throws Exception {
    UserAccount user = UserAccount.create(email, "Test User", true);
    user.grant(role(Roles.ROLE_VIEWER));
    assignId(user, id);
    return user;
  }

  private static void assignId(UserAccount user, UUID id) {
    try {
      Field idField = UserAccount.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RoleEntity role(String name) {
    try {
      var constructor = RoleEntity.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      RoleEntity role = constructor.newInstance();
      Field idField = RoleEntity.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(role, (short) 3);
      Field nameField = RoleEntity.class.getDeclaredField("name");
      nameField.setAccessible(true);
      nameField.set(role, name);
      return role;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("linking is never attempted on the strength of an email alone")
  void neverMatchesOnEmailWhenSubjectIsUnknownAndUnverified() {
    when(identities.findByProviderAndProviderUserId(eq(PROVIDER), anyString()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                resolver.resolve(new OidcIdentity(PROVIDER, "a-different-sub", EMAIL, false, "X")))
        .isInstanceOf(AuthException.class);

    verify(users, never()).findWithRolesByEmail(anyString());
  }
}
