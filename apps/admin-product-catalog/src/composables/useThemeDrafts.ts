import { ref, type Ref } from 'vue';

import { normalizeTheme, type ThemeConfig } from '@/lib/themeEngine';

/**
 * Named theme drafts plus the in-progress working copy, kept in THIS browser (localStorage).
 * They are per-admin scratch space - "try three directions, publish one" - not shared state; the
 * published theme itself lives on the server. Quota/private-mode failures are swallowed: losing a
 * scratch draft is annoying, crashing the editor is worse.
 */
export interface SavedDraft {
  readonly id: string;
  readonly name: string;
  readonly savedAt: string;
  readonly config: ThemeConfig;
}

const DRAFTS_KEY = 'cw_theme_drafts_v1';
const WORKING_KEY = 'cw_theme_working_v1';

function readDrafts(): SavedDraft[] {
  try {
    const raw = localStorage.getItem(DRAFTS_KEY);
    const arr: unknown = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((d): d is SavedDraft => !!d && typeof d === 'object' && typeof (d as SavedDraft).id === 'string')
      .map((d) => ({ ...d, name: String(d.name ?? 'Untitled'), config: normalizeTheme(d.config) }));
  } catch {
    return [];
  }
}

const drafts: Ref<SavedDraft[]> = ref(readDrafts());

function persist(): void {
  try {
    localStorage.setItem(DRAFTS_KEY, JSON.stringify(drafts.value));
  } catch {
    // Quota exceeded or storage blocked: the in-memory list still works for this session.
  }
}

export interface UseThemeDrafts {
  readonly drafts: Ref<SavedDraft[]>;
  save(name: string, config: ThemeConfig): SavedDraft;
  rename(id: string, name: string): void;
  remove(id: string): void;
  readWorking(): ThemeConfig | null;
  writeWorking(config: ThemeConfig | null): void;
}

export function useThemeDrafts(): UseThemeDrafts {
  return {
    drafts,
    save(name, config) {
      const draft: SavedDraft = {
        id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`,
        name: name.trim() || `Draft ${drafts.value.length + 1}`,
        savedAt: new Date().toISOString(),
        config: JSON.parse(JSON.stringify(config)) as ThemeConfig,
      };
      drafts.value = [draft, ...drafts.value].slice(0, 30);
      persist();
      return draft;
    },
    rename(id, name) {
      drafts.value = drafts.value.map((d) => (d.id === id ? { ...d, name: name.trim() || d.name } : d));
      persist();
    },
    remove(id) {
      drafts.value = drafts.value.filter((d) => d.id !== id);
      persist();
    },
    readWorking() {
      try {
        const raw = localStorage.getItem(WORKING_KEY);
        return raw ? normalizeTheme(JSON.parse(raw)) : null;
      } catch {
        return null;
      }
    },
    writeWorking(config) {
      try {
        if (config) localStorage.setItem(WORKING_KEY, JSON.stringify(config));
        else localStorage.removeItem(WORKING_KEY);
      } catch {
        // See above.
      }
    },
  };
}
