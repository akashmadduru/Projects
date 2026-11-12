<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter, RouterLink } from 'vue-router';

import BrandLogo from '@/components/BrandLogo.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import { googleAuthorizationUrl } from '@/lib/http';
import { rememberPostLoginRedirect, safeRedirectTarget } from '@/lib/oauthRedirect';
import { ApiError } from '@/lib/problem';
import { DEFAULT_AUTHENTICATED_ROUTE } from '@/router/guards';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

const form = reactive({ email: '', password: '' });
const touched = reactive({ email: false, password: false });
const submitting = ref(false);
const serverError = ref<ApiError | null>(null);

/** Shown after a successful registration bounced the user back here. */
const registeredEmail = computed<string | null>(() => {
  const value = route.query['registered'];
  return typeof value === 'string' && value.length > 0 ? value : null;
});

/** Where the guard wanted the user to go before it intercepted them. */
const redirectTarget = computed<string>(
  () => safeRedirectTarget(route.query['redirect']) ?? DEFAULT_AUTHENTICATED_ROUTE,
);

// Deliberately loose: the authoritative check is the server's, and an
// over-strict client regex rejects valid addresses (quoted locals, new TLDs).
const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const emailError = computed<string | null>(() => {
  if (form.email.trim().length === 0) return 'Email is required.';
  if (!EMAIL_SHAPE.test(form.email.trim())) return 'Enter a valid email address.';
  return null;
});
const passwordError = computed<string | null>(() =>
  form.password.length === 0 ? 'Password is required.' : null,
);
const formValid = computed<boolean>(() => emailError.value === null && passwordError.value === null);

async function submit(): Promise<void> {
  touched.email = true;
  touched.password = true;
  serverError.value = null;
  if (!formValid.value || submitting.value) {
    return;
  }

  submitting.value = true;
  try {
    await auth.login({ email: form.email.trim(), password: form.password });
    await router.push(redirectTarget.value);
  } catch (error: unknown) {
    // The backend returns one indistinguishable 401 for "wrong password" and
    // "no such user" so the endpoint cannot be used as a user directory. The UI
    // must not attempt to be more specific than the API deliberately is.
    serverError.value =
      error instanceof ApiError
        ? error
        : new ApiError('Sign-in failed unexpectedly.', { status: 0 });
  } finally {
    submitting.value = false;
  }
}

function signInWithGoogle(): void {
  // A top-level navigation, NOT a fetch: the flow redirects to Google and back,
  // which XHR cannot follow and CORS would block outright.
  rememberPostLoginRedirect(redirectTarget.value);
  window.location.assign(googleAuthorizationUrl());
}
</script>

<template>
  <div class="flex min-h-screen flex-col items-center justify-center gap-6 p-4">
    <div class="flex flex-col items-center gap-2 text-center">
      <BrandLogo tone="surface" large :tag="false" />
      <p class="text-sm font-semibold uppercase tracking-[0.2em] opacity-70">Store admin console</p>
    </div>
    <div class="data-card w-full max-w-md">
      <div class="card-body p-0">
        <h1 class="card-title text-2xl">Sign in</h1>
        <p class="text-sm opacity-70">Welcome back to Cartwise Admin</p>

        <div
          v-if="registeredEmail"
          class="alert alert-success mt-2"
          role="status"
          data-test="registered-notice"
        >
          Account created for {{ registeredEmail }}. Sign in to continue.
        </div>

        <ProblemAlert
          v-if="serverError"
          class="mt-2"
          :message="serverError.message"
          :correlation-id="serverError.correlationId"
        />

        <form class="mt-2 flex flex-col gap-3" novalidate @submit.prevent="submit">
          <div class="form-control">
            <label class="label" for="login-email">
              <span class="label-text">Email</span>
            </label>
            <input
              id="login-email"
              v-model="form.email"
              class="input input-bordered w-full"
              type="email"
              autocomplete="username"
              data-test="email"
              :aria-invalid="touched.email && emailError !== null"
              :aria-describedby="touched.email && emailError ? 'login-email-error' : undefined"
              @blur="touched.email = true"
            />
            <p
              v-if="touched.email && emailError"
              id="login-email-error"
              class="mt-1 text-sm text-error"
              data-test="email-error"
            >
              {{ emailError }}
            </p>
          </div>

          <div class="form-control">
            <label class="label" for="login-password">
              <span class="label-text">Password</span>
            </label>
            <input
              id="login-password"
              v-model="form.password"
              class="input input-bordered w-full"
              type="password"
              autocomplete="current-password"
              data-test="password"
              :aria-invalid="touched.password && passwordError !== null"
              :aria-describedby="
                touched.password && passwordError ? 'login-password-error' : undefined
              "
              @blur="touched.password = true"
            />
            <p
              v-if="touched.password && passwordError"
              id="login-password-error"
              class="mt-1 text-sm text-error"
              data-test="password-error"
            >
              {{ passwordError }}
            </p>
          </div>

          <button class="btn btn-primary mt-2" type="submit" data-test="submit" :disabled="submitting">
            <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
            Sign in
          </button>
        </form>

        <div class="divider text-xs">or</div>

        <button
          class="btn btn-outline w-full"
          type="button"
          data-test="google"
          @click="signInWithGoogle"
        >
          Sign in with Google
        </button>

        <p class="mt-4 text-center text-sm">
          No account?
          <RouterLink class="link link-primary" :to="{ name: 'register' }">Register</RouterLink>
        </p>
      </div>
    </div>
  </div>
</template>
