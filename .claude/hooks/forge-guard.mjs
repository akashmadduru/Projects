// PreToolUse(Bash): git safety that permission rules cannot express.
// - no commit while the target checkout is on main/master/dev
// - subagents never push; nobody pushes straight to a protected branch
import { readStdin, git, deny, PROTECTED_BRANCHES, norm } from './lib.mjs';
const input = readStdin();
const cmd = input?.tool_input?.command || '';
if (!/\bgit\b/.test(cmd)) process.exit(0);
const cwd = norm(input.cwd || process.cwd());
// Each git invocation in the command line, with the directory it runs in.
const parts = cmd.split(/&&|\|\||;|\n/);
let dir = cwd;
for (const raw of parts) {
  const p = raw.trim();
  const cd = p.match(/^cd\s+("?)([^"]+)\1$/);
  if (cd) { dir = cd[2]; continue; }
  // Tokenise (quotes respected) and skip git's global options to find the subcommand.
  const tokens = (p.match(/"[^"]*"|'[^']*'|\S+/g) || []).map((t) => t.replace(/^["']|["']$/g, ''));
  const g = tokens.findIndex((t) => t === 'git' || /(^|\/)git(\.exe)?$/.test(t));
  if (g < 0) continue;
  let i = g + 1;
  let target = dir;
  while (i < tokens.length && tokens[i].startsWith('-')) {
    const opt = tokens[i];
    if (opt === '-C') { target = tokens[i + 1]; i += 2; continue; }
    if (['-c', '--git-dir', '--work-tree', '--namespace', '--exec-path'].includes(opt)) { i += 2; continue; }
    i += 1; // --no-pager, -P, --bare, --git-dir=..., -c=... etc.
  }
  const sub = tokens[i];
  const rest = tokens.slice(i + 1).join(' ');
  if (!sub) continue;
  if (sub === 'commit') {
    const branch = git(['rev-parse', '--abbrev-ref', 'HEAD'], target);
    if (PROTECTED_BRANCHES.includes(branch)) deny(`refusing to commit on protected branch '${branch}'. Work on a forge/<id>-<slug> branch in a worktree.`);
  }
  if (sub === 'push') {
    if (input.agent_type) deny(`agents never push (agent: ${input.agent_type}). The CEO prepares the push and the Owner approves it.`);
    const refs = rest.split(/\s+/).filter((t) => t && !t.startsWith('-'));
    if (refs.slice(1).some((r) => PROTECTED_BRANCHES.includes(r.split(':').pop()))) deny('direct pushes to main/master/dev are not allowed; open a pull request.');
  }
}
process.exit(0);
