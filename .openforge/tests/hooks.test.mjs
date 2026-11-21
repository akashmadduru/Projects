// Regression tests for the OpenForge hooks: each rule is exercised with the JSON the runtime sends.
// Run: node --test .openforge/tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..', '..');
const hook = (name, input, env = {}) => {
  const r = spawnSync('node', [path.join(root, '.claude', 'hooks', name)], {
    input: JSON.stringify(input), encoding: 'utf8', env: { ...process.env, CLAUDE_PROJECT_DIR: root, ...env },
  });
  let decision = null;
  try { decision = JSON.parse(r.stdout).hookSpecificOutput.permissionDecision; } catch {}
  return { code: r.status, decision, out: r.stdout + r.stderr };
};
const repo = (branch) => {
  const d = mkdtempSync(path.join(tmpdir(), 'forge-'));
  const g = (...a) => execFileSync('git', a, { cwd: d, stdio: 'ignore' });
  g('init', '-q', '-b', branch); g('-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '--allow-empty', '-m', 'x');
  return d;
};

test('guard: commit on main is denied, on a forge branch allowed', () => {
  const main = repo('main');
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: 'git commit -m x' }, cwd: main }).decision, 'deny');
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: `git -C ${main} commit -m x` }, cwd: root }).decision, 'deny');
  // global options before the subcommand (found in a live test that slipped through)
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: `git -C "${main}" -c user.email=t@t -c user.name=t commit --allow-empty -m "x"` }, cwd: root }).decision, 'deny');
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: `cd ${main} && git --no-pager commit -m x` }, cwd: root }).decision, 'deny');
  const forge = repo('forge/F-20261119-01-demo');
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: 'git commit -m x' }, cwd: forge }).decision, null);
});
test('guard: subagents never push; nobody pushes to main', () => {
  const forge = repo('forge/F-20261119-01-demo');
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: 'git push -u origin forge/F-20261119-01-demo' }, cwd: forge, agent_type: 'forge-backend-engineer' }).decision, 'deny');
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: 'git push origin HEAD:main' }, cwd: forge }).decision, 'deny');
  assert.equal(hook('forge-guard.mjs', { tool_input: { command: 'git push -u origin forge/F-20261119-01-demo' }, cwd: forge }).decision, null);
});
test('protect: role write boundaries', () => {
  const p = (agent, file) => hook('forge-protect.mjs', { agent_type: agent, tool_input: { file_path: path.join(root, file) } }).decision;
  assert.equal(p('forge-reviewer', '.openforge/work/F-1/review.json'), 'deny');
  assert.equal(p('forge-security', 'services/x/A.java'), 'deny');
  assert.equal(p('forge-backend-engineer', '.claude/settings.json'), 'deny');
  assert.equal(p('forge-backend-engineer', '.openforge/worktrees/F-1/.claude/hooks/forge-guard.mjs'), 'deny');
  assert.equal(p('forge-backend-engineer', 'services/order-service/order-service-app/src/main/A.java'), 'deny');
  assert.equal(p('forge-backend-engineer', '.openforge/worktrees/F-1/services/a/a-app/src/main/A.java'), null);
  assert.equal(p('forge-qa', '.openforge/worktrees/F-1/services/a/a-app/src/main/A.java'), 'deny');
  assert.equal(p('forge-qa', '.openforge/worktrees/F-1/services/a/a-app/src/test/ATest.java'), null);
  assert.equal(p('forge-cpo', '.openforge/work/F-1/requirements.md'), null);
  assert.equal(p('forge-cpo', 'docs/x.md'), 'deny');
  assert.equal(p('forge-architect', '.openforge/knowledge/decisions/0001-x.md'), null);
  assert.equal(p(undefined, 'services/x/A.java'), null); // main session: governed by permissions
});
test('gate: self-review, stale SHA and missing evidence fail', () => {
  const wt = repo('forge/F-20261119-99-demo');
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: wt, encoding: 'utf8' }).trim();
  const fakeRoot = mkdtempSync(path.join(tmpdir(), 'forge-root-'));
  const wd = path.join(fakeRoot, '.openforge', 'work', 'F-20261119-99');
  mkdirSync(wd, { recursive: true });
  const w = (f, s) => writeFileSync(path.join(wd, f), typeof s === 'string' ? s : JSON.stringify(s));
  w('state.json', { worktree: wt, base_sha: head, implementer: 'forge-backend-engineer' });
  for (const f of ['objective.md', 'design.md', 'tasks.md']) w(f, 'x');
  w('requirements.md', 'AC-1 a\nAC-2 b');
  w('complexity.md', 'Total 1, class **S**');
  w('evidence.md', '| AC-1 | t1 | PASS |');
  w('review.json', { reviewer: 'forge-backend-engineer', sha: head, verdict: 'approve' });
  w('security.json', { reviewer: 'forge-security', sha: 'deadbeef00', verdict: 'approve' });
  const r = spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-gate.mjs'), 'F-20261119-99'], { encoding: 'utf8', env: { ...process.env, CLAUDE_PROJECT_DIR: fakeRoot } });
  assert.equal(r.status, 1);
  assert.match(r.stdout, /self-review is not allowed/);
  assert.match(r.stdout, /security.json reviewed deadbeef/);
  assert.match(r.stdout, /AC-2 has no passing evidence/);
  // fixed: independent reviewers on the head SHA, full evidence → PASS
  w('review.json', { reviewer: 'forge-reviewer', sha: head, verdict: 'approve' });
  w('security.json', { reviewer: 'forge-security', sha: head, verdict: 'approve' });
  w('evidence.md', '| AC-1 | t1 | PASS |\n| AC-2 | t2 | PASS |');
  const ok = spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-gate.mjs'), 'F-20261119-99'], { encoding: 'utf8', env: { ...process.env, CLAUDE_PROJECT_DIR: fakeRoot } });
  assert.equal(ok.status, 0, ok.stdout);
  // committing the work record after review keeps the review valid; a code change does not
  const g = (...a) => execFileSync('git', ['-c', 'user.email=t@t', '-c', 'user.name=t', ...a], { cwd: wt, stdio: 'ignore' });
  mkdirSync(path.join(wt, '.openforge', 'work', 'F-20261119-99'), { recursive: true });
  writeFileSync(path.join(wt, '.openforge', 'work', 'F-20261119-99', 'log.md'), 'x');
  g('add', '.'); g('commit', '-q', '-m', 'record');
  const rec = spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-gate.mjs'), 'F-20261119-99'], { encoding: 'utf8', env: { ...process.env, CLAUDE_PROJECT_DIR: fakeRoot } });
  assert.equal(rec.status, 0, rec.stdout);
  writeFileSync(path.join(wt, 'code.txt'), 'changed');
  g('add', '.'); g('commit', '-q', '-m', 'code after review');
  const stale = spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-gate.mjs'), 'F-20261119-99'], { encoding: 'utf8', env: { ...process.env, CLAUDE_PROJECT_DIR: fakeRoot } });
  assert.equal(stale.status, 1);
  assert.match(stale.stdout, /code changed after it/);
});
test('verify: maps changed paths to checks (dry run)', () => {
  const r = spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-verify.mjs'), '--dry-run'], { cwd: root, encoding: 'utf8' });
  assert.match(r.stdout, /would run: gradle test/);
  assert.match(r.stdout, /apps\/product-catalog: vitest/);
});
test('gate: size limit counts production lines only (G-3)', () => {
  const wt = repo('forge/F-20261119-98-demo');
  const base = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: wt, encoding: 'utf8' }).trim();
  const g = (...a) => execFileSync('git', ['-c', 'user.email=t@t', '-c', 'user.name=t', ...a], { cwd: wt, stdio: 'ignore' });
  mkdirSync(path.join(wt, 'svc', 'src', 'test'), { recursive: true });
  mkdirSync(path.join(wt, 'svc', 'src', 'main'), { recursive: true });
  writeFileSync(path.join(wt, 'svc', 'src', 'test', 'BigTest.java'), 'x\n'.repeat(900));
  writeFileSync(path.join(wt, 'README.md'), 'x\n'.repeat(500));
  writeFileSync(path.join(wt, 'svc', 'src', 'main', 'A.java'), 'x\n'.repeat(250));
  g('add', '.'); g('commit', '-q', '-m', 'work');
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: wt, encoding: 'utf8' }).trim();
  const fakeRoot = mkdtempSync(path.join(tmpdir(), 'forge-root-'));
  const wd = path.join(fakeRoot, '.openforge', 'work', 'F-20261119-98');
  mkdirSync(wd, { recursive: true });
  const w = (f, s) => writeFileSync(path.join(wd, f), typeof s === 'string' ? s : JSON.stringify(s));
  w('state.json', { worktree: wt, base_sha: base, implementer: 'forge-backend-engineer' });
  for (const f of ['objective.md', 'design.md', 'tasks.md']) w(f, 'x');
  w('requirements.md', 'AC-1 a'); w('evidence.md', '| AC-1 | t | PASS |');
  w('review.json', { reviewer: 'forge-reviewer', sha: head, verdict: 'approve' });
  w('security.json', { reviewer: 'forge-security', sha: head, verdict: 'approve' });
  const run = () => spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-gate.mjs'), 'F-20261119-98'], { encoding: 'utf8', env: { ...process.env, CLAUDE_PROJECT_DIR: fakeRoot } });
  w('complexity.md', 'Total 1, class **S**');           // 250 production lines <= S 300; tests/docs ignored
  let r = run(); assert.equal(r.status, 0, r.stdout); assert.match(r.stdout, /250 production lines/);
  writeFileSync(path.join(wt, 'svc', 'src', 'main', 'B.java'), 'x\n'.repeat(100));
  g('add', '.'); g('commit', '-q', '-m', 'more');
  const head2 = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: wt, encoding: 'utf8' }).trim();
  w('review.json', { reviewer: 'forge-reviewer', sha: head2, verdict: 'approve' });
  w('security.json', { reviewer: 'forge-security', sha: head2, verdict: 'approve' });
  r = run(); assert.equal(r.status, 1); assert.match(r.stdout, /350 changed production lines/);
});
test('verify: env reference check runs when config or env docs change (G-4)', () => {
  const r = spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-verify.mjs'), '--dry-run'], { cwd: root, encoding: 'utf8' });
  assert.match(r.stdout, /would run: env reference --check/);
});
test('gate: stacked PRs are checked part by part (G-8)', () => {
  const wt = repo('forge/F-20261119-97-demo');
  const sha = () => execFileSync('git', ['rev-parse', 'HEAD'], { cwd: wt, encoding: 'utf8' }).trim();
  const g = (...a) => execFileSync('git', ['-c', 'user.email=t@t', '-c', 'user.name=t', ...a], { cwd: wt, stdio: 'ignore' });
  const base = sha();
  mkdirSync(path.join(wt, 'svc', 'src', 'main'), { recursive: true });
  writeFileSync(path.join(wt, 'svc', 'src', 'main', 'A.java'), 'x\n'.repeat(250)); g('add', '.'); g('commit', '-q', '-m', 'part1');
  const p1 = sha();
  writeFileSync(path.join(wt, 'svc', 'src', 'main', 'B.java'), 'x\n'.repeat(250)); g('add', '.'); g('commit', '-q', '-m', 'part2');
  const p2 = sha();
  const fakeRoot = mkdtempSync(path.join(tmpdir(), 'forge-root-'));
  const wd = path.join(fakeRoot, '.openforge', 'work', 'F-20261119-97');
  mkdirSync(path.join(wd, 'history'), { recursive: true });
  const w = (f, s) => writeFileSync(path.join(wd, f), typeof s === 'string' ? s : JSON.stringify(s));
  for (const f of ['objective.md', 'design.md', 'tasks.md']) w(f, 'x');
  w('requirements.md', 'AC-1 a'); w('evidence.md', '| AC-1 | t | PASS |'); w('complexity.md', 'Total 1, class **S**');
  w('review.json', { reviewer: 'forge-reviewer', sha: p2, verdict: 'approve' });
  w('security.json', { reviewer: 'forge-security', sha: p2, verdict: 'approve' });
  const run = () => spawnSync('node', [path.join(root, '.claude', 'hooks', 'forge-gate.mjs'), 'F-20261119-97'], { encoding: 'utf8', env: { ...process.env, CLAUDE_PROJECT_DIR: fakeRoot } });
  w('state.json', { worktree: wt, base_sha: base, implementer: 'forge-backend-engineer' });
  let r = run(); assert.equal(r.status, 1); assert.match(r.stdout, /500 changed production lines/);       // whole diff over S 300
  w('state.json', { worktree: wt, base_sha: base, implementer: 'forge-backend-engineer', pr_split: [`${base}..${p1}`, `${p1}..${p2}`] });
  r = run(); assert.equal(r.status, 1); assert.match(r.stdout, /part 1 head .* no approving forge-reviewer/); // part 1 not yet approved
  w('history/review-s1.json', { reviewer: 'forge-reviewer', sha: p1, verdict: 'approve' });
  w('history/security-s1.json', { reviewer: 'forge-security', sha: p1, verdict: 'approve' });
  r = run(); assert.equal(r.status, 0, r.stdout); assert.match(r.stdout, /part 2 .*250 production lines/);
  w('state.json', { worktree: wt, base_sha: base, implementer: 'forge-backend-engineer', pr_split: [`${base}..${base}`, `${p1}..${p2}`] });
  r = run(); assert.equal(r.status, 1); assert.match(r.stdout, /must be contiguous/);                     // gap in the ranges
});
