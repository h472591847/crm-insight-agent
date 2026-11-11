# Task 1 Report: 类型与 Store 生成态

## Status

**DONE_WITH_CONCERNS** — implementation complete and type-check passed; commit failed due to missing git user identity.

## What changed

### `src/types/chat.ts`
- Extended `ChatMessage` with optional `replyStatus?: 'streaming' | 'done'`.

### `src/stores/chat.ts`
- Added `isGenerating` ref and module-level `doneStatusTimer` + `clearDoneStatusTimer()`.
- Added `startTextGeneration(botMsgId)` — pushes empty bot bubble with `replyStatus: 'streaming'`, sets `isGenerating=true`.
- Added `failTextGeneration(botMsgId, errorText?)` — clears timer, unlocks, writes failure text, clears `replyStatus`.
- Added `unlockGeneration(botMsgId?)` — clears timer, unlocks; optionally clears `replyStatus` on matching message.
- `updateBotMessage`: new-message branch sets `replyStatus: 'streaming'`; existing messages leave `replyStatus` untouched (does not overwrite `done`).
- `finishBotMessage`: sets `replyStatus: 'done'`, clears `isGenerating`, schedules 1.5s clear of `done` via timer.
- `clearChat`: clears timer first, empties list, sets `isGenerating=false`.
- Exported: `isGenerating`, `startTextGeneration`, `failTextGeneration`, `unlockGeneration`.

## Verification

```text
npm run type-check
> vue-tsc --build
Exit code: 0
```

Passed with no errors.

## Commit

**COMMIT_FAILED** — `git commit` refused: Author identity unknown (`user.name` / `user.email` unset). Per constraints, did not run `git config`.

Staged and ready to commit:
- `src/types/chat.ts`
- `src/stores/chat.ts`

Intended message:
```text
feat(chat): add generating state and replyStatus on messages
```

## Self-review

| Brief item | Result |
|---|---|
| `replyStatus` on `ChatMessage` | OK |
| `isGenerating` + timer helpers | OK |
| `startTextGeneration` / `failTextGeneration` / `unlockGeneration` verbatim behavior | OK |
| `updateBotMessage` new branch + preserve existing status | OK |
| `finishBotMessage` done + 1.5s clear | OK |
| `clearChat` clears timer + unlocks | OK |
| Return exports | OK |
| YAGNI / no extra files | OK |
| No test framework added | OK |

No functional deviations from the brief. No UI/SSE wiring (out of scope for Task 1).

## Concerns

1. **COMMIT_FAILED** — user must set git identity (or commit with env `GIT_AUTHOR_*`) and run the staged commit.
2. Unrelated unstaged/untracked paths left alone: `docs/`, `.superpowers/` (report itself is under `.superpowers/sdd/`).
