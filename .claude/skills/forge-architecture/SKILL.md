---
name: forge-architecture
description: OpenForge step 3–4. CTO scores complexity, Architect designs, CTO accepts the design; L/XL designs go to the Owner.
---
# Step 3–4: Complexity and architecture
1. Delegate to `forge-cto`: score complexity → complexity.md.
   - XL: stop. Present the CTO's proposed split to the Owner.
2. Class S: the Architect may write a short design.md (current behaviour, change, AC map, files).
   Class M+: full design.md plus an ADR in `.openforge/knowledge/decisions/`.
   Delegate to `forge-architect` with the class and the paths.
3. Delegate to `forge-cto`: "review design.md, append ACCEPT/REJECT to complexity.md". On REJECT send
   the reasons to the Architect once more; a second REJECT goes to the Owner.
4. Class L: present design.md to the Owner and wait for explicit approval; record it in complexity.md
   (`Owner approval: <date> <quote>`).
Gate: complexity.md has the class and `ACCEPT`; L has the Owner approval line.
