<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo } from '@/lib/adminApi';
import { applySeller, getMySeller } from '@/lib/sellerApi';
import { useAuthStore } from '@/stores/auth';
import type { Seller } from '@/types/seller';

/** "Become a seller": 3-step KYC wizard, plus status view once submitted. */
const auth = useAuthStore();
const router = useRouter();

type Err = { message: string; correlationId: string | null };
const seller = ref<Seller | null>(null);
const loading = ref(true);
const error = ref<Err | null>(null);
const submitting = ref(false);
const step = ref(1);
const touched = ref(false);

const GSTIN_RE = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;
const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;

const form = reactive({
  legalName: '',
  displayName: '',
  email: auth.user?.email ?? '',
  phone: '',
  gstin: '',
  pan: '',
  bankAccountNumber: '',
  ifsc: '',
  line1: '',
  line2: '',
  city: '',
  state: '',
  pincode: '',
});

/** Editable when never applied, still a draft, or rejected (re-apply). */
const editable = computed<boolean>(
  () => seller.value === null || seller.value.status === 'DRAFT' || seller.value.status === 'REJECTED',
);

function stepError(n: number): string | null {
  if (n === 1) {
    if (form.legalName.trim().length < 2) return 'Legal name is required.';
    if (form.displayName.trim().length < 2) return 'Store display name is required.';
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) return 'Enter a valid email.';
    if (form.phone.trim().length < 8) return 'Enter a valid phone number.';
  }
  if (n === 2) {
    const gstin = form.gstin.trim().toUpperCase();
    const pan = form.pan.trim().toUpperCase();
    if (!GSTIN_RE.test(gstin)) return 'GSTIN format is invalid (e.g. 29ABCDE1234F1Z5).';
    if (!PAN_RE.test(pan)) return 'PAN format is invalid (e.g. ABCDE1234F).';
    if (gstin.substring(2, 12) !== pan) return 'PAN must match characters 3-12 of the GSTIN.';
    if (!/^[0-9]{6,18}$/.test(form.bankAccountNumber.trim())) return 'Bank account number must be 6-18 digits.';
    if (!IFSC_RE.test(form.ifsc.trim().toUpperCase())) return 'IFSC format is invalid (e.g. HDFC0001234).';
  }
  if (n === 3) {
    if (form.line1.trim().length === 0) return 'Address line 1 is required.';
    if (form.city.trim().length === 0) return 'City is required.';
    if (form.state.trim().length === 0) return 'State is required.';
    if (!/^[0-9]{6}$/.test(form.pincode.trim())) return 'Pincode must be 6 digits.';
  }
  return null;
}

const currentError = computed<string | null>(() => stepError(step.value));

function next(): void {
  touched.value = true;
  if (currentError.value === null) {
    touched.value = false;
    step.value += 1;
  }
}

async function submit(submitForReview: boolean): Promise<void> {
  touched.value = true;
  if (submitForReview && currentError.value !== null) return;
  submitting.value = true;
  error.value = null;
  try {
    seller.value = await applySeller({
      legalName: form.legalName.trim(),
      displayName: form.displayName.trim(),
      email: form.email.trim(),
      phone: form.phone.trim(),
      gstin: form.gstin.trim().toUpperCase(),
      pan: form.pan.trim().toUpperCase(),
      bankAccountNumber: form.bankAccountNumber.trim(),
      ifsc: form.ifsc.trim().toUpperCase(),
      pickupAddress: {
        line1: form.line1.trim(),
        line2: form.line2.trim() || undefined,
        city: form.city.trim(),
        state: form.state.trim(),
        pincode: form.pincode.trim(),
      },
      submit: submitForReview,
    });
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    submitting.value = false;
  }
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    seller.value = await getMySeller();
    const s = seller.value;
    if (s) {
      form.legalName = s.legalName;
      form.displayName = s.displayName;
      form.email = s.email ?? form.email;
      form.phone = s.phone ?? '';
      form.gstin = s.gstin ?? '';
      form.pan = s.pan ?? '';
      form.ifsc = s.ifsc ?? '';
      form.line1 = s.pickupAddress?.line1 ?? '';
      form.line2 = s.pickupAddress?.line2 ?? '';
      form.city = s.pickupAddress?.city ?? '';
      form.state = s.pickupAddress?.state ?? '';
      form.pincode = s.pickupAddress?.pincode ?? '';
      // The bank account number is never echoed back; the seller must re-enter it to edit.
    }
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    loading.value = false;
  }
}

const refreshing = ref(false);
/** Approval grants ROLE_SELLER server-side; a token refresh picks it up. */
async function enterPortal(): Promise<void> {
  refreshing.value = true;
  error.value = null;
  try {
    await auth.refreshAccessToken();
    await auth.loadCurrentUser();
    await router.push('/seller/listings');
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    refreshing.value = false;
  }
}

const statusBadge: Readonly<Record<string, string>> = {
  DRAFT: 'badge-ghost',
  SUBMITTED: 'badge-info',
  APPROVED: 'badge-success',
  REJECTED: 'badge-error',
  SUSPENDED: 'badge-warning',
};

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="mx-auto max-w-2xl">
    <h1 class="mb-4 text-2xl font-semibold">Become a seller</h1>

    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>

    <template v-else>
      <ProblemAlert v-if="error" class="mb-4" :message="error.message" :correlation-id="error.correlationId" />

      <div v-if="seller" class="data-card mb-4 p-4" data-test="seller-status">
        <div class="flex items-center justify-between">
          <div>
            <div class="font-semibold">{{ seller.displayName }}</div>
            <div class="text-xs opacity-70">{{ seller.legalName }}</div>
          </div>
          <span class="badge" :class="statusBadge[seller.status]">{{ seller.status }}</span>
        </div>
        <p v-if="seller.status === 'SUBMITTED'" class="mt-3 text-sm">
          Your application is under review. We will email you once it is decided.
        </p>
        <p v-if="seller.status === 'REJECTED'" class="mt-3 text-sm text-error">
          Rejected: {{ seller.rejectionReason ?? 'no reason given' }}. Update the details below and re-submit.
        </p>
        <p v-if="seller.status === 'SUSPENDED'" class="mt-3 text-sm text-warning">
          Your seller account is suspended. Contact support.
        </p>
        <div v-if="seller.status === 'APPROVED'" class="mt-3">
          <p class="text-sm">You are approved. Open the portal (this refreshes your session to pick up the seller role).</p>
          <button class="btn btn-primary btn-sm mt-2" type="button" :disabled="refreshing" data-test="seller-enter" @click="enterPortal">
            <span v-if="refreshing" class="loading loading-spinner loading-xs" aria-hidden="true" />
            Open seller portal
          </button>
        </div>
        <dl v-if="seller.status !== 'DRAFT'" class="mt-3 grid grid-cols-2 gap-2 text-sm">
          <dt class="opacity-60">GSTIN</dt><dd class="font-mono">{{ seller.gstin ?? '-' }}</dd>
          <dt class="opacity-60">PAN</dt><dd class="font-mono">{{ seller.pan ?? '-' }}</dd>
          <dt class="opacity-60">Bank account</dt>
          <dd class="font-mono">{{ seller.bankAccountLast4 ? `•••• ${seller.bankAccountLast4}` : '-' }}</dd>
          <dt class="opacity-60">IFSC</dt><dd class="font-mono">{{ seller.ifsc ?? '-' }}</dd>
        </dl>
      </div>

      <div v-if="editable" class="data-card p-4" data-test="seller-wizard">
        <ul class="steps mb-4 w-full">
          <li class="step" :class="{ 'step-primary': step >= 1 }">Business</li>
          <li class="step" :class="{ 'step-primary': step >= 2 }">Tax &amp; bank</li>
          <li class="step" :class="{ 'step-primary': step >= 3 }">Pickup address</li>
        </ul>

        <form class="grid grid-cols-2 gap-3" novalidate @submit.prevent="step < 3 ? next() : submit(true)">
          <template v-if="step === 1">
            <label class="form-control col-span-2">
              <span class="label-text">Legal / business name</span>
              <input v-model="form.legalName" class="input input-bordered w-full" maxlength="120" data-test="apply-legal" />
            </label>
            <label class="form-control col-span-2">
              <span class="label-text">Store display name</span>
              <input v-model="form.displayName" class="input input-bordered w-full" maxlength="80" data-test="apply-display" />
            </label>
            <label class="form-control">
              <span class="label-text">Contact email</span>
              <input v-model="form.email" type="email" class="input input-bordered w-full" />
            </label>
            <label class="form-control">
              <span class="label-text">Phone</span>
              <input v-model="form.phone" class="input input-bordered w-full" maxlength="20" />
            </label>
          </template>

          <template v-else-if="step === 2">
            <label class="form-control">
              <span class="label-text">GSTIN</span>
              <input v-model="form.gstin" class="input input-bordered w-full uppercase" maxlength="15" data-test="apply-gstin" />
            </label>
            <label class="form-control">
              <span class="label-text">PAN</span>
              <input v-model="form.pan" class="input input-bordered w-full uppercase" maxlength="10" data-test="apply-pan" />
            </label>
            <label class="form-control">
              <span class="label-text">Bank account number</span>
              <input v-model="form.bankAccountNumber" class="input input-bordered w-full" inputmode="numeric" autocomplete="off" maxlength="18" />
            </label>
            <label class="form-control">
              <span class="label-text">IFSC</span>
              <input v-model="form.ifsc" class="input input-bordered w-full uppercase" maxlength="11" />
            </label>
          </template>

          <template v-else>
            <label class="form-control col-span-2">
              <span class="label-text">Address line 1</span>
              <input v-model="form.line1" class="input input-bordered w-full" maxlength="120" />
            </label>
            <label class="form-control col-span-2">
              <span class="label-text">Address line 2 <span class="opacity-60">(optional)</span></span>
              <input v-model="form.line2" class="input input-bordered w-full" maxlength="120" />
            </label>
            <label class="form-control">
              <span class="label-text">City</span>
              <input v-model="form.city" class="input input-bordered w-full" />
            </label>
            <label class="form-control">
              <span class="label-text">State</span>
              <input v-model="form.state" class="input input-bordered w-full" />
            </label>
            <label class="form-control">
              <span class="label-text">Pincode</span>
              <input v-model="form.pincode" class="input input-bordered w-full" inputmode="numeric" maxlength="6" />
            </label>
          </template>

          <p v-if="touched && currentError" class="col-span-2 text-sm text-error" data-test="apply-validation">
            {{ currentError }}
          </p>

          <div class="col-span-2 flex justify-between gap-2">
            <button class="btn btn-ghost" type="button" :disabled="step === 1 || submitting" @click="step -= 1">Back</button>
            <div class="flex gap-2">
              <button class="btn btn-outline" type="button" :disabled="submitting" @click="submit(false)">Save draft</button>
              <button v-if="step < 3" class="btn btn-primary" type="submit">Next</button>
              <button v-else class="btn btn-primary" type="submit" :disabled="submitting" data-test="apply-submit">
                <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
                Submit for review
              </button>
            </div>
          </div>
        </form>
      </div>
    </template>
  </section>
</template>
