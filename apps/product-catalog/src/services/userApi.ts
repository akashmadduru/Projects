/**
 * User profile and address API client (user-service via gateway)
 */

import { apiFetch } from '../lib/http'
import type { Address, AddressRequest, UpdateProfileRequest, UserProfile } from '../types'

export class UserApiClient {
  private baseUrl = '/api/users/me'

  getProfile(): Promise<UserProfile> {
    return apiFetch<UserProfile>(this.baseUrl)
  }

  updateProfile(request: UpdateProfileRequest): Promise<UserProfile> {
    return apiFetch<UserProfile>(this.baseUrl, { method: 'PUT', body: request })
  }

  listAddresses(): Promise<Address[]> {
    return apiFetch<Address[]>(`${this.baseUrl}/addresses`)
  }

  createAddress(request: AddressRequest): Promise<Address> {
    return apiFetch<Address>(`${this.baseUrl}/addresses`, { method: 'POST', body: request })
  }

  updateAddress(id: string, request: AddressRequest): Promise<Address> {
    return apiFetch<Address>(`${this.baseUrl}/addresses/${id}`, { method: 'PUT', body: request })
  }

  deleteAddress(id: string): Promise<void> {
    return apiFetch<void>(`${this.baseUrl}/addresses/${id}`, { method: 'DELETE' })
  }

  setDefaultAddress(id: string): Promise<Address> {
    return apiFetch<Address>(`${this.baseUrl}/addresses/${id}/default`, { method: 'PUT' })
  }
}

export const userApi = new UserApiClient()
