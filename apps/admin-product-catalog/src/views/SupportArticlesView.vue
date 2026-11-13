<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo } from '@/lib/adminApi';
import { createArticle, deleteArticle, listArticles, updateArticle } from '@/lib/trustApi';
import { FAQ_CATEGORIES, type FaqArticle, type FaqCategory } from '@/types/trust';

/** Help-centre FAQ editor (markdown-lite body). */
type ErrorInfo = { message: string; correlationId: string | null };

const rows = ref<readonly FaqArticle[]>([]);
const loading = ref(false);
const error = ref<ErrorInfo | null>(null);
const actionError = ref<ErrorInfo | null>(null);
const saving = ref(false);
const toDelete = ref<FaqArticle | null>(null);

const editing = ref(false);
const editId = ref<string | null>(null);
const form = reactive<{
  slug: string;
  category: FaqCategory;
  title: string;
  body: string;
  keywords: string;
  published: boolean;
}>({ slug: '', category: 'OTHER', title: '', body: '', keywords: '', published: true });

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    rows.value = await listArticles();
  } catch (e: unknown) {
    error.value = errorInfo(e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

function startNew(): void {
  editId.value = null;
  Object.assign(form, { slug: '', category: 'OTHER', title: '', body: '', keywords: '', published: true });
  editing.value = true;
}

function startEdit(a: FaqArticle): void {
  editId.value = a.id;
  Object.assign(form, {
    slug: a.slug,
    category: a.category,
    title: a.title,
    body: a.body,
    keywords: a.keywords.join(', '),
    published: a.published,
  });
  editing.value = true;
}

async function save(): Promise<void> {
  saving.value = true;
  actionError.value = null;
  const payload = {
    slug: form.slug.trim(),
    category: form.category,
    title: form.title.trim(),
    body: form.body,
    keywords: form.keywords
      .split(',')
      .map((k) => k.trim())
      .filter((k) => k.length > 0),
    published: form.published,
  };
  try {
    if (editId.value) {
      await updateArticle(editId.value, payload);
    } else {
      await createArticle(payload);
    }
    editing.value = false;
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    saving.value = false;
  }
}

async function confirmDelete(): Promise<void> {
  if (!toDelete.value) return;
  saving.value = true;
  actionError.value = null;
  try {
    await deleteArticle(toDelete.value.id);
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    saving.value = false;
    toDelete.value = null;
  }
}

onMounted(load);
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Help centre articles</h1>
      <button class="btn btn-primary btn-sm" type="button" data-test="article-new" @click="startNew">New article</button>
    </div>

    <ProblemAlert v-if="actionError" class="mb-4" :message="actionError.message" :correlation-id="actionError.correlationId" />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="rows.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No articles yet</h2>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Help centre articles</caption>
          <thead>
            <tr>
              <th scope="col">Title</th>
              <th scope="col">Category</th>
              <th scope="col">Published</th>
              <th scope="col" class="text-right">Helpful</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in rows" :key="a.id" data-test="article-row">
              <td>
                <div class="font-medium">{{ a.title }}</div>
                <div class="font-mono text-xs opacity-60">{{ a.slug }}</div>
              </td>
              <td class="text-xs">{{ a.category }}</td>
              <td>
                <span class="badge badge-sm" :class="a.published ? 'badge-success' : 'badge-ghost'">
                  {{ a.published ? 'live' : 'draft' }}
                </span>
              </td>
              <td class="text-right text-sm tabular-nums">👍 {{ a.helpfulYes ?? 0 }} · 👎 {{ a.helpfulNo ?? 0 }}</td>
              <td class="text-right">
                <button class="btn btn-outline btn-xs mr-1" type="button" @click="startEdit(a)">Edit</button>
                <button class="btn btn-outline btn-error btn-xs" type="button" @click="toDelete = a">Delete</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="editing" class="modal modal-open" role="dialog" aria-modal="true" data-test="article-form">
      <form class="modal-box data-card-elevated max-w-2xl" @submit.prevent="save">
        <h3 class="text-lg font-semibold">{{ editId ? 'Edit article' : 'New article' }}</h3>
        <div class="mt-3 flex flex-col gap-2">
          <input v-model="form.title" class="input input-bordered input-sm" placeholder="Title" aria-label="Title" required />
          <div class="flex gap-2">
            <input v-model="form.slug" class="input input-bordered input-sm flex-1 font-mono" placeholder="slug" aria-label="Slug" required />
            <select v-model="form.category" class="select select-bordered select-sm" aria-label="Category">
              <option v-for="c in FAQ_CATEGORIES" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>
          <input v-model="form.keywords" class="input input-bordered input-sm" placeholder="Keywords, comma separated" aria-label="Keywords" />
          <textarea v-model="form.body" class="textarea textarea-bordered h-48" placeholder="Body (markdown-lite)" aria-label="Body" required />
          <label class="label cursor-pointer justify-start gap-2">
            <input v-model="form.published" type="checkbox" class="checkbox checkbox-sm" />
            <span class="label-text">Published</span>
          </label>
        </div>
        <div class="modal-action">
          <button class="btn btn-ghost" type="button" :disabled="saving" @click="editing = false">Cancel</button>
          <button class="btn btn-primary" type="submit" :disabled="saving">
            <span v-if="saving" class="loading loading-spinner loading-sm" aria-hidden="true" />
            Save
          </button>
        </div>
      </form>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="editing = false" />
    </div>

    <ConfirmDialog
      :open="toDelete !== null"
      title="Delete this article?"
      :message="`“${toDelete?.title ?? ''}” will be removed from the help centre.`"
      confirm-label="Delete article"
      :submitting="saving"
      @confirm="confirmDelete"
      @cancel="toDelete = null"
    />
  </section>
</template>
