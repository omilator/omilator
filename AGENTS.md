# AGENTS.md

This repository is **public** and mirrored to GitHub at https://github.com/omilator/omilator.
Every commit is publicly visible — treat all work as public-facing.

## Do not commit private/transient context
Never create or commit session, handoff, or local-only context as tracked files:
- No `SESSION_NOTES.md`, `*_NOTES.md`, `HANDOFF*.md`, `CONTINUATION_PROMPT.md`, `*_NEXT_SESSION.md`
- No `.claude/`, `.opencode/` local configs
- No internal scratch/audit/planning docs unless they are intentional public design docs
- No secrets — `.env`, API keys, tokens

These are gitignored as a safety net, but **do not create them in the first place**. Keep scratchpads in session memory or outside the repo (e.g. `~/.agent-inbox/`), never as tracked files.

## Commits
- Conventional style (`feat:`, `fix:`, `chore:`, `docs:`).
- History is public — keep it clean.

## Remotes
`git push origin` fans out to both Forgejo and GitHub (dual push-URL) — a single push mirrors to both.
