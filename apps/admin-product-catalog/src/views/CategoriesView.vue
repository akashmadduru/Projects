<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import {
  createCategory,
  deleteCategory,
  errorInfo,
  isCategoryActive,
  listCategories,
  updateCategory,
} from '@/lib/adminApi';
import { useAuthStore } from '@/stores/auth';
import type { Category } from '@/types/commerce';

/**
 * Category tree (two levels, matching product-service's depth rule). Writes
 * need CATALOG_MANAGER or ADMIN - the backend enforces it; the buttons are
 * hidden otherwise. Delete is refused (409) for categories with products or
 * subcategories, so "Deactivate" is the normal way to retire one.
 */
type ErrorInfo = { message: string; correlationId: string | null };

const auth = useAuthStore();
const rows = ref<readonly Category[]>([]);
const loading = ref(false);
const error = ref<ErrorInfo | null>(null);
const actionError = ref<ErrorInfo | null>(null);

const editing = ref<Category | null>(null);
const formOpen = ref(false);
const saving = ref(false);
const form = reactive({ name: '', description: '', parentCategoryId: '', active: true });
const nameTouched = ref(false);

const toDelete = ref<Category | null>(null);
const deleting = ref(false);

const tree = computed(() => {
  const roots = rows.value.filter((c) => !c.parentCategoryId);
  const known = new Set(rows.value.map((c) => c.id));
  const orphans = rows.value.filter((c) => c.parentCategoryId && !known.has(c.parentCategoryId));
  const out: { cat: Category; depth: number }[] = [];
  for (const r of [...roots].sort((a, b) => a.name.localeCompare(b.name))) {
    out.push({ cat: r, depth: 0 });
    rows.value
      .filter((c) => c.parentCategoryId === r.id)
      .sort((a, b) => a.name.localeCompare(b.name))
      .forEach((c) => out.push({ cat: c, depth: 1 }));
  }
  orphans.forEach((c) => out.push({ cat: c, depth: 1 }));
  return out;
});

/** Only top-level categories can be parents (depth limit is one level). */
const parentOptions = computed(() =>
  rows.value.filter((c) => !c.parentCategoryId && c.id !== editing.value?.id),
);

const nameError = computed<string>(() => {
  const n = form.name.trim();
  if (!n) return 'Name is required.';
  if (n.length > 120) return 'Name must be 120 characters or fewer.';
  return '';
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    rows.value = (await listCategories()).content;
  } catch (e: unknown) {
    error.value = errorInfo(e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

function openCreate(parentId = ''): void {
  editing.value = null;
  Object.assign(form, { name: '', description: '', parentCategoryId: parentId, active: true });
  nameTouched.value = false;
  actionError.value = null;
  formOpen.value = true;
}

function openEdit(c: Category): void {
  editing.value = c;
  Object.assign(form, {
    name: c.name,
    description: c.description ?? '',
    parentCategoryId: c.parentCategoryId ?? '',
    active: isCategoryActive(c),
  });
  nameTouched.value = false;
  actionError.value = null;
  formOpen.value = true;
}

async function save(): Promise<void> {
  nameTouched.value = true;
  if (nameError.value || saving.value) return;
  saving.value = true;
  actionError.value = null;
  const payload = {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    parentCategoryId: form.parentCategoryId || null,
    active: form.active,
  };
  try {
    if (editing.value) {
      await updateCategory(editing.value.id, payload, editing.value.version);
    } else {
      await createCategory(payload);
    }
    formOpen.value = false;
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    saving.value = false;
  }
}

async function toggleActive(c: Category): Promise<void> {
  actionError.value = null;
  try {
    await updateCategory(
      c.id,
      { name: c.name, description: c.description ?? undefined, active: !isCategoryActive(c) },
      c.version,
    );
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  }
}

async function confirmDelete(): Promise<void> {
  if (!toDelete.value) return;
  deleting.value = true;
  actionError.value = null;
  try {
    await deleteCategory(toDelete.value.id);
    toDelete.value = null;
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
    toDelete.value = null;
  } finally {
    deleting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between gap-4">
      <h1 class="text-2xl font-semibold">Categories</h1>
      <button
        v-if="auth.canManageCatalog"
        class="btn btn-primary btn-sm"
        type="button"
        data-test="category-new"
        @click="openCreate()"
      >
        New category
      </button>
    </div>

    <ProblemAlert
      v-if="actionError && !formOpen"
      class="mb-4"
      :message="actionError.message"
      :correlation-id="actionError.correlationId"
    />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading categories…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="tree.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No categories yet</h2>
      <p class="mt-1 text-sm opacity-70">Create a top-level category, then add subcategories under it.</p>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Categories</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Description</th>
              <th scope="col">Status</th>
              <th v-if="auth.canManageCatalog" scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="{ cat, depth } in tree" :key="cat.id" data-test="category-row">
              <td :class="depth ? 'pl-10' : 'font-medium'">
                <span v-if="depth" class="mr-1 opacity-40">└</span>{{ cat.name }}
              </td>
              <td class="max-w-md truncate text-sm opacity-80">{{ cat.description ?? '' }}</td>
              <td>
                <span class="badge badge-sm" :class="isCategoryActive(cat) ? 'badge-success' : 'badge-ghost'">
                  {{ isCategoryActive(cat) ? 'Active' : 'Inactive' }}
                </span>
              </td>
              <td v-if="auth.canManageCatalog" class="text-right">
                <div class="flex justify-end gap-1">
                  <button
                    v-if="depth === 0"
                    class="btn btn-ghost btn-xs"
                    type="button"
                    @click="openCreate(cat.id)"
                  >
                    Add sub
                  </button>
                  <button class="btn btn-ghost btn-xs" type="button" data-test="category-edit" @click="openEdit(cat)">
                    Edit
                  </button>
                  <button class="btn btn-ghost btn-xs" type="button" @click="toggleActive(cat)">
                    {{ isCategoryActive(cat) ? 'Deactivate' : 'Activate' }}
                  </button>
                  <button
                    class="btn btn-ghost btn-xs text-error"
                    type="button"
                    data-test="category-delete"
                    @click="toDelete = cat"
                  >
                    Delete
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Create / edit -->
    <div v-if="formOpen" class="modal modal-open" role="dialog" aria-modal="true">
      <form class="modal-box data-card-elevated flex flex-col gap-3" novalidate @submit.prevent="save">
        <h3 class="text-lg font-semibold">{{ editing ? 'Edit category' : 'New category' }}</h3>
        <ProblemAlert v-if="actionError" :message="actionError.message" :correlation-id="actionError.correlationId" />
        <label class="form-control">
          <span class="label-text mb-1">Name</span>
          <input
            v-model="form.name"
            class="input input-bordered"
            :class="{ 'input-error': nameTouched && nameError }"
            maxlength="120"
            data-test="category-name"
            @blur="nameTouched = true"
          />
          <span v-if="nameTouched && nameError" class="mt-1 text-xs text-error">{{ nameError }}</span>
        </label>
        <label class="form-control">
          <span class="label-text mb-1">Description <span class="opacity-60">(optional)</span></span>
          <textarea v-model="form.description" class="textarea textarea-bordered" rows="2" />
        </label>
        <label class="form-control">
          <span class="label-text mb-1">Parent</span>
          <select v-model="form.parentCategoryId" class="select select-bordered" :disabled="editing !== null">
            <option value="">None (top level)</option>
            <option v-for="p in parentOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <span v-if="editing" class="mt-1 text-xs opacity-60">The parent cannot be changed after creation.</span>
        </label>
        <label class="label cursor-pointer justify-start gap-3">
          <input v-model="form.active" type="checkbox" class="toggle toggle-primary" />
          <span class="label-text">Active (visible in the storefront)</span>
        </label>
        <div class="modal-action">
          <button class="btn btn-ghost" type="button" :disabled="saving" @click="formOpen = false">Cancel</button>
          <button class="btn btn-primary" type="submit" :disabled="saving" data-test="category-save">
            <span v-if="saving" class="loading loading-spinner loading-xs" aria-hidden="true" />
            Save
          </button>
        </div>
      </form>
    </div>

    <ConfirmDialog
      :open="toDelete !== null"
      title="Delete category?"
      :message="`Delete “${toDelete?.name ?? ''}”? This is refused if any product or subcategory still uses it — deactivate it instead.`"
      confirm-label="Delete"
      :submitting="deleting"
      @confirm="confirmDelete"
      @cancel="toDelete = null"
    />
  </section>
</template>
