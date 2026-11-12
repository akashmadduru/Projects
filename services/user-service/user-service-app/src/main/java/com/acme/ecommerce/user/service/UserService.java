package com.acme.ecommerce.user.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.api.dto.AddressRequest;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import com.acme.ecommerce.user.api.dto.UpdateProfileRequest;
import com.acme.ecommerce.user.api.dto.UserProfileResponse;
import com.acme.ecommerce.user.domain.Address;
import com.acme.ecommerce.user.domain.UserProfile;
import com.acme.ecommerce.user.repository.AddressRepository;
import com.acme.ecommerce.user.repository.UserProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile and address use cases. Every method is scoped to the calling user id. */
@Service
public class UserService {

  static final int MAX_ADDRESSES = 20;

  private final UserProfileRepository profiles;
  private final AddressRepository addresses;

  public UserService(UserProfileRepository profiles, AddressRepository addresses) {
    this.profiles = profiles;
    this.addresses = addresses;
  }

  // --- profile ---------------------------------------------------------------

  @Transactional
  public UserProfileResponse getProfile(UUID userId, String email) {
    return toResponse(ensureProfile(userId, email));
  }

  @Transactional
  public UserProfileResponse updateProfile(UUID userId, String email, UpdateProfileRequest req) {
    UserProfile profile = ensureProfile(userId, email);
    profile.setDisplayName(blankToNull(req.displayName()));
    profile.setPhone(blankToNull(req.phone()));
    profile.touch(Instant.now());
    return toResponse(profiles.save(profile));
  }

  private UserProfile ensureProfile(UUID userId, String email) {
    profiles.insertIfAbsent(userId, email);
    return profiles.findById(userId).orElseThrow();
  }

  // --- addresses -------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<AddressResponse> listAddresses(UUID userId) {
    return addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId).stream()
        .map(UserService::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public AddressResponse getAddress(UUID userId, UUID id) {
    return toResponse(find(userId, id));
  }

  @Transactional
  public AddressResponse createAddress(UUID userId, AddressRequest req) {
    // Serialise per-user address writes so the cap and the single-default rule cannot race.
    lockUser(userId);
    long count = addresses.countByUserId(userId);
    if (count >= MAX_ADDRESSES) {
      throw new CodedException(
          ErrorCode.ADDRESS_LIMIT, "At most " + MAX_ADDRESSES + " addresses can be saved");
    }
    Address address = Address.forUser(userId, Instant.now());
    apply(address, req);
    address.setDefaultAddress(count == 0);
    return toResponse(addresses.save(address));
  }

  @Transactional
  public AddressResponse updateAddress(UUID userId, UUID id, AddressRequest req) {
    Address address = find(userId, id);
    apply(address, req);
    address.setUpdatedAt(Instant.now());
    return toResponse(addresses.save(address));
  }

  @Transactional
  public void deleteAddress(UUID userId, UUID id) {
    lockUser(userId);
    Address address = find(userId, id);
    boolean wasDefault = address.isDefaultAddress();
    addresses.delete(address);
    addresses.flush();
    if (wasDefault) {
      addresses
          .findFirstByUserIdOrderByCreatedAtDesc(userId)
          .ifPresent(
              next -> {
                next.setDefaultAddress(true);
                next.setUpdatedAt(Instant.now());
              });
    }
  }

  @Transactional
  public AddressResponse setDefault(UUID userId, UUID id) {
    lockUser(userId);
    Address address = find(userId, id);
    if (!address.isDefaultAddress()) {
      addresses.clearDefault(userId);
      address = find(userId, id);
      address.setDefaultAddress(true);
      address.setUpdatedAt(Instant.now());
    }
    return toResponse(addresses.save(address));
  }

  // --- helpers ---------------------------------------------------------------

  private Address find(UUID userId, UUID id) {
    return addresses
        .findByIdAndUserId(id, userId)
        .orElseThrow(() -> new CodedException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found"));
  }

  /** Per-user transaction-scoped advisory lock, keyed on a hash of the user id. */
  private void lockUser(UUID userId) {
    profiles.lockUser(userId.getMostSignificantBits() ^ userId.getLeastSignificantBits());
  }

  private static void apply(Address a, AddressRequest r) {
    a.setLabel(blankToNull(r.label()));
    a.setFullName(r.fullName().trim());
    a.setPhone(blankToNull(r.phone()));
    a.setLine1(r.line1().trim());
    a.setLine2(blankToNull(r.line2()));
    a.setCity(r.city().trim());
    a.setState(blankToNull(r.state()));
    a.setPostalCode(r.postalCode().trim());
    a.setCountry(r.country().trim().toUpperCase(Locale.ROOT));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static UserProfileResponse toResponse(UserProfile p) {
    return new UserProfileResponse(
        p.getUserId(), p.getEmail(), p.getDisplayName(), p.getPhone(), p.getCreatedAt());
  }

  private static AddressResponse toResponse(Address a) {
    return new AddressResponse(
        a.getId(),
        a.getLabel(),
        a.getFullName(),
        a.getPhone(),
        a.getLine1(),
        a.getLine2(),
        a.getCity(),
        a.getState(),
        a.getPostalCode(),
        a.getCountry(),
        a.isDefaultAddress(),
        a.getCreatedAt(),
        a.getUpdatedAt());
  }
}
