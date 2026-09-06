# ADR-0001: Hermes Mobileをフォークする

Status: Accepted  
Date: 2026-08-31

## Context

専用Android appには、chat、streaming、session、cron、settings、
encrypted storage、notification、Hermes protocol supportが必要。

## Options

1. blank Kotlin project
2. React Native/Expo
3. `Hy4ri/hermes-mobile` fork
4. `adebnar/hermes-android` fork
5. Web/PWA wrapper

## Decision

既存fork `msidragonsasus/hermes-mobile` のdevを使用する。2026-09-06の採用監査SHAは
`fd485a69990b358f81d8dd4c12c761e7d2555d24`。旧5c8ab7eへresetしない。
PR 00で実checkoutに整合した理由は [ADR-0004](0004-audit-aligned-foundation.md)。

## Rationale

- required infrastructureが既にある
- Apache-2.0
- Kotlin/Compose
- active development
- Navigation 3 / Compose / Room 3 / SQLCipher。Hiltは未導入
- Hermes-specific management surface

## Consequences

Positive:

- 初期実装が速い
- protocol integrationを再発明しない
- test/CIを利用できる

Negative:

- upstream merge conflict
- 現在の単一module/hardcoded registryを段階refactor
- UIをPersonal AI Shellへ再構成する必要

## Guardrails

- upstream code cleanupと新機能を同一commitに混ぜない
- Phase 1は`:app`内のpackage境界と手動DI。module/Hiltは後続独立判断
- upstream参照先を維持（remote設定は実際に必要な工程で確認）
- GPL clientからcode copy禁止
