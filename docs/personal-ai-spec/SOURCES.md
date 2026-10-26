# Source Notes

2026-08-31の設計根拠を、2026-09-06採用監査に合わせて訂正。
最新の実装保証ではなく、[監査報告](../personal-ai/AUDIT_REPORT.md) のSHA・検証日を参照する。

## Hermes Agent

- Official repository: `NousResearch/hermes-agent`
- Programmatic Integration:
  - TUI Gateway JSON-RPC over stdio/WebSocket
  - API Server HTTP/SSE
  - session, approval, clarification, steering, streaming events
- Extending the Dashboard:
  - runtime UI plugins
  - backend `plugin_api.py`
  - routes mounted at `/api/plugins/<name>/`
- Open issue:
  - `NousResearch/hermes-agent#84406`
  - `/v1/runs`, session continuity, approvalsの組合せに関する報告
  - 実装時に最新状態を再確認すること

## Android base clients

### Selected base

- `Hy4ri/hermes-mobile`
- Apache-2.0
- Kotlin / Compose / Material 3 / Navigation 3 / Room 3 + SQLCipher、Hilt未導入
- ユーザーfork `msidragonsasus/hermes-mobile` の監査 `dev` commit:
  `fd485a69990b358f81d8dd4c12c761e7d2555d24`

### UX reference only

- `adebnar/hermes-android`
- GPL-3.0
- Mission Control / Agent Activityの概念を参考
- source codeはApache derivativeへコピーしない

## Android official guidance

- Navigation 3 modularization
- feature `api`/`impl`
- Hilt multibindings（将来候補。現在採用済み・Phase 1必須という根拠ではない）
- Dynamic Code Loading security guidance
- external/remote code loadingは避ける
