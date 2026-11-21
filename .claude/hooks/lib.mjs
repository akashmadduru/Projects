// Shared helpers for OpenForge hooks. No dependencies: Node built-ins only.
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

export const PROTECTED_BRANCHES = ['main', 'master', 'dev'];
export const GOVERNANCE = [/(^|\/)CLAUDE\.md$/, /(^|\/)\.openforge\/constitution\.md$/, /(^|\/)\.claude\//, /(^|\/)\.github\/workflows\//];

export function readStdin() {
  try { return JSON.parse(readFileSync(0, 'utf8') || '{}'); } catch { return {}; }
}
export const norm = (p) => (p || '').replace(/[\\]/g, '/');
export function projectDir() { return norm(process.env.CLAUDE_PROJECT_DIR || process.cwd()); }
export function git(args, cwd) {
  try { return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim(); }
  catch { return ''; }
}
export function deny(reason) {
  process.stdout.write(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: `OpenForge: ${reason}` } }));
  process.exit(0);
}
export function readJson(p) { try { return JSON.parse(readFileSync(p, 'utf8')); } catch { return null; } }
export function workDir(id) { return path.join(projectDir(), '.openforge', 'work', id); }
export { existsSync, readFileSync };
