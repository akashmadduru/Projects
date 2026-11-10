<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';

import ThemeToggle from '@/components/ThemeToggle.vue';
import { useAuthStore } from '@/stores/auth';

/**
 * Relocates what used to live in `AppShell.vue`'s header (signed-in-as,
 * roles, sign-out, theme) into a dedicated screen, while `AppShell.vue`
 * keeps its own theme toggle for quick access — this is a second surface for
 * it, not a replacement.
 */
const auth = useAuthStore();
const router = useRouter();
const signingOut = ref(false);

async function signOut(): Promise<void> {
  signingOut.value = true;
  try {
    await auth.logout();
  } finally {
    signingOut.value = false;
    await router.push({ name: 'login' });
  }
}
</script>

<template>
  <section class="flex flex-col gap-4">
    <h1 class="text-2xl font-semibold">Settings</h1>

    <div class="data-card">
      <h2 class="text-sm font-medium uppercase tracking-wide opacity-60">Account</h2>
      <dl class="mt-3 flex flex-col gap-2 text-sm">
        <div class="flex items-center justify-between gap-4">
          <dt class="opacity-70">Signed in as</dt>
          <dd data-test="settings-email">{{ auth.user?.email ?? '—' }}</dd>
        </div>
        <div class="flex items-center justify-between gap-4">
          <dt class="opacity-70">Roles</dt>
          <dd data-test="settings-roles">{{ auth.user?.roles.join(', ') || '—' }}</dd>
        </div>
      </dl>
      <button
        class="btn btn-outline btn-sm mt-4"
        type="button"
        data-test="settings-logout"
        :disabled="signingOut"
        @click="signOut"
      >
        <span v-if="signingOut" class="loading loading-spinner loading-xs" aria-hidden="true" />
        Sign out
      </button>
    </div>

    <div class="data-card">
      <h2 class="text-sm font-medium uppercase tracking-wide opacity-60">Appearance</h2>
      <div class="mt-3 flex items-center justify-between gap-4">
        <span class="text-sm opacity-70">Theme</span>
        <ThemeToggle />
      </div>
    </div>

    <div class="data-card">
      <p class="text-sm opacity-70">More settings coming soon.</p>
    </div>
  </section>
</template>
