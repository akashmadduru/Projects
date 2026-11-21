// The PR gate. usage: node .claude/hooks/forge-gate.mjs <work-id>
// Checks the work folder and git state; prints every failure; exit 0 only when all pass.
import path from 'node:path';
import fs from 'node:fs';
import { git, projectDir, readJson, readFileSync, existsSync, PROTECTED_BRANCHES } from './lib.mjs';

const id = process.argv[2];
if (!id) { console.error('usage: forge-gate.mjs <work-id>'); process.exit(2); }
const wd = path.join(projectDir(), '.openforge', 'work', id);
const fails = [];
const ok = [];
const check = (cond, pass, fail) => (cond ? ok.push(pass) : fails.push(fail));
const read = (f) => (existsSync(path.join(wd, f)) ? readFileSync(path.join(wd, f), 'utf8') : null);

const st = readJson(path.join(wd, 'state.json'));
if (!st) { console.log(`forge-gate ${id}: FAIL\n  - state.json missing in ${wd}`); process.exit(1); }
// --dir <checkout>: check that checkout instead of the recorded worktree (CI uses the PR checkout).
const dirArg = process.argv.indexOf('--dir');
const wt = dirArg > 0 ? path.resolve(process.argv[dirArg + 1])
  : path.isAbsolute(st.worktree || '') ? st.worktree : path.join(projectDir(), st.worktree || '');
const branch = git(['rev-parse', '--abbrev-ref', 'HEAD'], wt);
const head = git(['rev-parse', 'HEAD'], wt);

check(/^forge\/F-\d{8}-\d{2}-[a-z0-9-]+$/.test(branch) && !PROTECTED_BRANCHES.includes(branch), `branch ${branch}`, `branch '${branch}' is not forge/<id>-<slug>`);
check(!git(['status', '--porcelain'], wt), 'worktree clean', 'worktree has uncommitted changes');
for (const f of ['objective.md', 'requirements.md', 'complexity.md', 'design.md', 'tasks.md', 'evidence.md']) check(read(f), `${f} present`, `${f} missing`);

// Reviews: approve, for the current head, by someone other than the implementer.
const implementers = [].concat(st.implementer || []);
for (const f of ['review.json', 'security.json']) {
  const r = readJson(path.join(wd, f));
  if (!r) { fails.push(`${f} missing or not JSON`); continue; }
  check(r.verdict === 'approve', `${f} approve`, `${f} verdict is '${r.verdict}'`);
  // Valid if it reviewed the head, or if every commit after the reviewed SHA only touches this
  // objective's work record (the record is committed after review, which moves the head).
  const reviewed = r.sha && r.sha.length >= 7 ? git(['rev-parse', '--verify', `${r.sha}^{commit}`], wt) : '';
  const after = reviewed && reviewed !== head ? git(['diff', '--name-only', `${reviewed}..${head}`], wt).split('\n').filter(Boolean) : [];
  const onlyRecord = after.every((p) => p.startsWith(`.openforge/work/${id}/`));
  const isAncestor = reviewed && (reviewed === head || git(['merge-base', reviewed, head], wt) === reviewed);
  check(reviewed && isAncestor && onlyRecord, `${f} is for ${reviewed.slice(0, 8)}${reviewed === head ? ' (head)' : ' (+ work record only)'}`, `${f} reviewed ${String(r.sha).slice(0, 8)} but code changed after it (head ${head.slice(0, 8)}): re-review`);
  check(r.reviewer && !implementers.includes(r.reviewer), `${f} by ${r.reviewer}`, `${f} reviewer '${r.reviewer}' is an implementer: self-review is not allowed`);
}

// Evidence covers every acceptance criterion.
const req = read('requirements.md') || '';
const ev = read('evidence.md') || '';
const acs = [...new Set(req.match(/\bAC-\d+\b/g) || [])];
check(acs.length > 0, `${acs.length} acceptance criteria`, 'requirements.md has no AC-n acceptance criteria');
for (const ac of acs) {
  const line = ev.split('\n').find((l) => new RegExp(`\\b${ac}\\b`).test(l) && /\bpass(ed)?\b|✅/i.test(l));
  check(line, `${ac} evidenced`, `${ac} has no passing evidence line in evidence.md`);
}

// Diff size within the class limit unless the Owner overrode it.
const cx = read('complexity.md') || '';
const cls = (cx.match(/\bclass\b[^A-Za-z]*(S|M|L|XL)\b/i) || cx.match(/\*\*(S|M|L|XL)\*\*/) || [])[1];
const limit = { S: 300, M: 600, L: 600 }[(cls || '').toUpperCase()];
// Production lines only (constitution 4): tests, docs, Markdown and the work record do not count.
const notProduction = (f) => /(^|\/)src\/test\/|__tests__\/|\.(test|spec)\.[cm]?[jt]sx?$|^docs\/|\.md$|^\.openforge\//.test(f);
const prodLines = (range) => git(['diff', '--numstat', range], wt).split('\n').filter(Boolean)
  .map((l) => l.split('\t')).filter(([, , f]) => f && !notProduction(f))
  .reduce((n, [a, d]) => n + (a | 0) + (d | 0), 0);
const changed = prodLines(`${st.base_sha}..HEAD`);
// G-8: a class-L objective may ship as stacked PRs (constitution 4: "split into PRs under 600").
// state.pr_split lists contiguous "base..head" ranges from base_sha to the reviewed head. Each part must
// fit the limit, and every earlier part's head needs approving reviewer and security verdicts for that
// exact SHA in history/.
const parts = Array.isArray(st.pr_split) && st.pr_split.length > 1 ? st.pr_split.map((r) => String(r).split('..')) : null;
check(cls && cls.toUpperCase() !== 'XL', `class ${cls}`, `complexity class '${cls || 'unknown'}' (XL must be split; class must be recorded)`);
if (parts) {
  const sha = (s) => (s ? git(['rev-parse', '--verify', `${s}^{commit}`], wt) : '');
  const reviewedHead = sha((readJson(path.join(wd, 'review.json')) || {}).sha);
  let prev = sha(st.base_sha);
  let contiguous = true;
  for (const [b, h] of parts) { if (sha(b) !== prev) contiguous = false; prev = sha(h); }
  check(contiguous && prev && prev === reviewedHead, `pr_split: ${parts.length} contiguous parts ending at the reviewed head`, 'pr_split ranges must be contiguous from base_sha and end at the SHA in review.json');
  const hist = path.join(wd, 'history');
  const verdicts = existsSync(hist) ? fs.readdirSync(hist).map((n) => readJson(path.join(hist, n))).filter(Boolean) : [];
  parts.forEach(([b, h], k) => {
    const n = prodLines(`${b}..${h}`);
    check(!limit || n <= limit || /Owner override/i.test(cx), `part ${k + 1} (${b.slice(0, 8)}..${h.slice(0, 8)}): ${n} production lines (limit ${limit})`, `part ${k + 1} is ${n} production lines, over the class ${cls} limit of ${limit}`);
    if (k < parts.length - 1) for (const who of ['forge-reviewer', 'forge-security'])
      check(verdicts.some((v) => v.reviewer === who && v.verdict === 'approve' && sha(v.sha) === sha(h)), `part ${k + 1} approved by ${who}`, `part ${k + 1} head ${h.slice(0, 8)} has no approving ${who} verdict in history/`);
  });
} else {
  check(!limit || changed <= limit || /Owner override/i.test(cx), `diff ${changed} production lines (limit ${limit})`, `diff is ${changed} changed production lines, over the class ${cls} limit of ${limit} (needs an 'Owner override' line in complexity.md, or a pr_split of stacked parts)`);
}
if (cls && /^L$/i.test(cls)) check(/Owner approval:/i.test(cx), 'L design approved by Owner', 'class L needs an "Owner approval:" line in complexity.md');

console.log(`forge-gate ${id}: ${fails.length ? 'FAIL' : 'PASS'} (head ${head.slice(0, 8)} on ${branch})`);
for (const p of ok) console.log(`  ok   ${p}`);
for (const f of fails) console.log(`  FAIL ${f}`);
process.exit(fails.length ? 1 : 0);
