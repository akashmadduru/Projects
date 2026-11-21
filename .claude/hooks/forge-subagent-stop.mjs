// SubagentStop: an engineer or QA may not finish with uncommitted changes in the objective's worktree.
import path from 'node:path';
import { readdirSync } from 'node:fs';
import { readStdin, git, projectDir, readJson, existsSync } from './lib.mjs';
const input = readStdin();
if (input.stop_hook_active) process.exit(0);
const agent = input.agent_type || '';
if (!/^forge-(backend-engineer|frontend-engineer|qa)$/.test(agent)) process.exit(0);
const workRoot = path.join(projectDir(), '.openforge', 'work');
if (!existsSync(workRoot)) process.exit(0);
for (const id of readdirSync(workRoot)) {
  const st = readJson(path.join(workRoot, id, 'state.json'));
  if (!st || !['implement', 'test', 'review'].includes(st.step) || !st.worktree) continue;
  const wt = path.isAbsolute(st.worktree) ? st.worktree : path.join(projectDir(), st.worktree);
  const dirty = git(['status', '--porcelain'], wt);
  if (dirty) {
    process.stderr.write(`OpenForge: ${agent} is stopping with uncommitted changes in ${st.worktree}:\n${dirty.split('\n').slice(0, 10).join('\n')}\nCommit them with 'forge(${id}): ...' (or revert them) before finishing.`);
    process.exit(2);
  }
}
process.exit(0);
