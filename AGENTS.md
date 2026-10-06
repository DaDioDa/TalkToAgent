## Agent instructions

- **Reasoning:** Think and reason in English; communicate with the user in Chinese.
- **Commands:** This project is developed on Windows. Use Windows-compatible commands in Command Prompt or PowerShell.
- **Android development:** Use the installed `android` CLI from Command Prompt or PowerShell when it supports the task. For Android tasks, check relevant agent skills with `android skills list` or search with `android skills find <keyword>`, then follow applicable installed guidance.

- **更新既有 Android App：** 更新已安裝套件、指定裝置／user 或處理包名／簽章不符時，先讀 `tools/android/README.md` 與 `docs/spec-safe-android-update.md`，使用本專案安全更新入口。

## Agent skills

### Issue tracker

GitHub Issues via `gh`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Use the single-context layout. See `docs/agents/domain.md`.
