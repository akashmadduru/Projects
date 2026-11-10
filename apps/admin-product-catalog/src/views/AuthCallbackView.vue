<script setup lang="ts">
import { onMounted, shallowRef } from 'vue';
import { useRouter, RouterLink } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { takePostLoginRedirect } from '@/lib/oauthRedirect';
import { ApiError } from '@/lib/problem';
import { DEFAULT_AUTHENTICATED_ROUTE } from '@/router/guards';
import { useAuthStore } from '@/stores/auth';

/**
 * Landing point for the Google OAuth2 flow.
 *
 * auth-service has already set the HttpOnly refresh cookie and redirected here
 * with NOTHING in the URL — no token, no authorization code, not even a fragment.
 * That is the whole point: a URL ends up in browser history, in `Referer` headers
 * and in every proxy log in between, which is why the implicit flow was
 * deprecated. So the first access token is obtained the normal way, by calling
 * `POST /api/auth/refresh` with the cookie the browser is already holding.
 */
type CallbackState =
  | { readonly status: 'working' }
  | { readonly status: 'failed'; readonly message: string; readonly correlationId: string | null };

const auth = useAuthStore();
const router = useRouter();
const state = shallowRef<CallbackState>({ status: 'working' });

onMounted(async () => {
  try {
    await auth.completeFederatedLogin();
    await router.replace(takePostLoginRedirect() ?? DEFAULT_AUTHENTICATED_ROUTE);
  } catch (error: unknown) {
    const apiError = error instanceof ApiError ? error : null;
    state.value = {
      status: 'failed',
      message:
        apiError?.message ??
        'The sign-in could not be completed. Please try signing in again.',
      correlationId: apiError?.correlationId ?? null,
    };
  }
});
</script>

<template>
  <div class="flex min-h-screen items-center justify-center p-4">
    <div class="data-card w-full max-w-md">
      <div class="card-body items-center p-0 text-center">
        <template v-if="state.status === 'working'">
          <span class="loading loading-spinner loading-lg" aria-hidden="true" />
          <p role="status" data-test="callback-working">Completing sign-in…</p>
        </template>

        <template v-else>
          <h1 class="card-title">Sign-in failed</h1>
          <ProblemAlert
            class="mt-2 w-full"
            :message="state.message"
            :correlation-id="state.correlationId"
          />
          <RouterLink class="btn btn-primary mt-4" :to="{ name: 'login' }">
            Back to sign in
          </RouterLink>
        </template>
      </div>
    </div>
  </div>
</template>
