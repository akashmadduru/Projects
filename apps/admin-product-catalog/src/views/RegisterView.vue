<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter, RouterLink } from 'vue-router';

import BrandLogo from '@/components/BrandLogo.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import { ApiError } from '@/lib/problem';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const router = useRouter();

const form = reactive({ email: '', password: '', displayName: '' });
const touched = reactive({ email: false, password: false, displayName: false });
const submitting = ref(false);
const serverError = ref<ApiError | null>(null);

/**
 * Mirrors the server's `@Size(min = 12)`, which follows NIST SP 800-63B: length
 * is the property that resists offline cracking, so length is enforced and
 * composition rules are not. Client-side it exists only to save a round trip —
 * the server validates regardless.
 */
const MIN_PASSWORD_LENGTH = 12;
const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const emailError = computed<string | null>(() => {
  if (form.email.trim().length === 0) return 'Email is required.';
  if (!EMAIL_SHAPE.test(form.email.trim())) return 'Enter a valid email address.';
  return null;
});
const displayNameError = computed<string | null>(() =>
  form.displayName.trim().length === 0 ? 'Display name is required.' : null,
);
const passwordError = computed<string | null>(() => {
  if (form.password.length === 0) return 'Password is required.';
  if (form.password.length < MIN_PASSWORD_LENGTH) {
    return `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
  }
  return null;
});
const formValid = computed<boolean>(
  () => emailError.value === null && passwordError.value === null && displayNameError.value === null,
);

async function submit(): Promise<void> {
  touched.email = true;
  touched.password = true;
  touched.displayName = true;
  serverError.value = null;
  if (!formValid.value || submitting.value) {
    return;
  }

  submitting.value = true;
  try {
    const created = await auth.register({
      email: form.email.trim(),
      password: form.password,
      displayName: form.displayName.trim(),
    });
    await router.push({ name: 'login', query: { registered: created.email } });
  } catch (error: unknown) {
    // Registration is disabled by default in real deployments
    // (`auth.registration.enabled=false`). The feature is not hidden: the API's
    // own AUTH_REGISTRATION_DISABLED response drives what the user is told, so
    // the UI cannot drift out of sync with the deployment's actual policy.
    serverError.value =
      error instanceof ApiError
        ? error
        : new ApiError('Registration failed unexpectedly.', { status: 0 });
  } finally {
    submitting.value = false;
  }
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
        <h1 class="card-title text-2xl">Create an account</h1>

        <ProblemAlert
          v-if="serverError"
          class="mt-2"
          :message="serverError.message"
          :correlation-id="serverError.correlationId"
        />

        <form class="mt-2 flex flex-col gap-3" novalidate @submit.prevent="submit">
          <div class="form-control">
            <label class="label" for="register-name">
              <span class="label-text">Display name</span>
            </label>
            <input
              id="register-name"
              v-model="form.displayName"
              class="input input-bordered w-full"
              type="text"
              autocomplete="name"
              data-test="display-name"
              :aria-invalid="touched.displayName && displayNameError !== null"
              :aria-describedby="
                touched.displayName && displayNameError ? 'register-name-error' : undefined
              "
              @blur="touched.displayName = true"
            />
            <p
              v-if="touched.displayName && displayNameError"
              id="register-name-error"
              class="mt-1 text-sm text-error"
            >
              {{ displayNameError }}
            </p>
          </div>

          <div class="form-control">
            <label class="label" for="register-email">
              <span class="label-text">Email</span>
            </label>
            <input
              id="register-email"
              v-model="form.email"
              class="input input-bordered w-full"
              type="email"
              autocomplete="username"
              data-test="email"
              :aria-invalid="touched.email && emailError !== null"
              :aria-describedby="touched.email && emailError ? 'register-email-error' : undefined"
              @blur="touched.email = true"
            />
            <p
              v-if="touched.email && emailError"
              id="register-email-error"
              class="mt-1 text-sm text-error"
            >
              {{ emailError }}
            </p>
          </div>

          <div class="form-control">
            <label class="label" for="register-password">
              <span class="label-text">Password</span>
            </label>
            <input
              id="register-password"
              v-model="form.password"
              class="input input-bordered w-full"
              type="password"
              autocomplete="new-password"
              data-test="password"
              :aria-invalid="touched.password && passwordError !== null"
              :aria-describedby="
                touched.password && passwordError ? 'register-password-error' : 'register-password-hint'
              "
              @blur="touched.password = true"
            />
            <p
              v-if="touched.password && passwordError"
              id="register-password-error"
              class="mt-1 text-sm text-error"
              data-test="password-error"
            >
              {{ passwordError }}
            </p>
            <p v-else id="register-password-hint" class="mt-1 text-sm opacity-70">
              At least {{ MIN_PASSWORD_LENGTH }} characters.
            </p>
          </div>

          <button class="btn btn-primary mt-2" type="submit" data-test="submit" :disabled="submitting">
            <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
            Create account
          </button>
        </form>

        <p class="mt-4 text-center text-sm">
          Already have an account?
          <RouterLink class="link link-primary" :to="{ name: 'login' }">Sign in</RouterLink>
        </p>
      </div>
    </div>
  </div>
</template>
