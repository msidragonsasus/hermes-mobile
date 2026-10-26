# Personal AI Shell — 作業ルールの参照先

PR 00（2026-09-06）で重複ルールを整理した。旧版の「既存AGENTSへ追記する」方式は、
古いHilt前提やログ/padding規則を併存させるため廃止した。既存の有効な制約を保ち、
該当箇所を更新した [root AGENTS.md](../../../AGENTS.md) を必須作業ルールの正本とする。

- UI変更時: [DESIGN.md](../../../DESIGN.md) と [UX_SPEC.md](../UX_SPEC.md) の関連フロー
- 製品範囲: [PRODUCT_SPEC.md](../PRODUCT_SPEC.md)
- 構成/通信/DI: [ARCHITECTURE.md](../ARCHITECTURE.md)
- 承認/データ保護: [SECURITY.md](../SECURITY.md)
- Mini App契約: [MINIAPP_PLATFORM.md](../MINIAPP_PLATFORM.md)
- PR順序/受入条件/停止点: [IMPLEMENTATION_PLAN.md](../../personal-ai/IMPLEMENTATION_PLAN.md)
- 訂正理由: [ADR-0004](../adr/0004-audit-aligned-foundation.md)

この参照ファイルを読むことは後続実装や公開の承認ではない。
