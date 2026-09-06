# Phase 1 protocol compatibility

更新: 2026-09-06。実稼働Hermesへの接続試験は未実施。採用監査sourceと合成fixtureに基づく。

| 機能 | 経路・証拠 | Phase 1の判定と制限 |
|---|---|---|
| Session一覧 | 既存Dashboard WebSocket `session.list` の `sessions[].id/title` | 固定scopeのread成功時だけSupported。saved IDでありruntime IDへ自動変換しない |
| 実行観測 | 既存parserのmessage/tool/approval/clarifyイベント | socket listenerでserver/profile/process/generationを固定。クライアントが観測した範囲だけで、永続server Run ID・全Run履歴は提供しない |
| イベント欠落 | 既存seq/replay、replay truncation、epoch変更 | Unknownとして扱う。切断を実行失敗へ変換しない |
| Dashboard prompt | 既存固定scopeのrequest ID / ACK。暗号化OutgoingDraft | 送信前にscopeと接続を再確認。送信拒否はNotSent、timeout/切断後はUnknown。再接続で自動再送せず、確認ACK前に下書きを削除しない |
| Dashboard approval | 既存 `approval.respond`、元scope/runtime/request ID | once/denyのみ。未広告choice・別scope・欠落・失効・送信試行済みを拒否。ACK前は成功扱いにしない。実稼働版のACK/回復契約は未確認 |
| Dashboard clarification | 既存 `clarify.respond`、元request ID + question ID | 複数questionを区別。入力を保持し、replayで送信済み/結果不明を再回答可能に戻さない。質問IDなしを別質問へ補完しない |
| Attention通知 | Android通知content intent / 検証済みRequestRef | 本文や回答をintentへ複製せず、question/scope別に識別。通知上の直接承認なし。元要求が未観測なら詳細は操作不可 |
| Dashboard ready | `skin/change_events/heartbeat/replay_epoch`等の限定handshake | 全機能の対応広告とは解釈しない |
| Dashboard read失敗 | RPC error code、timeout、scope不一致、壊れたDTO | method-not-foundはUnsupported、認証・不正応答は理由付きBlocked、接続未確定はUnknown |
| API Server discovery | `/v1/capabilities`専用DTOとoffline decoder | HTTP 401/403、404、timeout、壊れた応答を区別。Dashboard認証・DTOと共用しない。実HTTP接続は未実施 |
| API Server run/approval/idempotency | sourceが広告するfeatureと `runs_idempotency` | 広告値を保持するだけ。継続session、exact approval、idempotencyのlive結合検証なし。live writeはdisabled |
| Mini App実行 | Phase 1ではmanifest基盤のみ | 未実装renderer・遠隔配布・任意外部操作はdisabled |
| Mini App native登録 | 明示的にコンパイル済みのChat/Activity NavKey | Development等の業務featureは未登録。fixture用登録をlive registryへ混ぜない |
| Mini App host version | manifestのminimum version / 厳密SemVer比較 | 既存 `1.0-dev` を都合よく正規化しない。比較不能なhostではminimum条件を満たしたと扱わない |

新しいreadは既存socket、認証、request ID、pending解決・timeoutを利用する。
切断中やsocket送信拒否時にキューへ残さず、接続を自動作成しない。
scope切替後の結果を新しいprofileへ採用しない。未知endpointの推測や、送信後のprotocol fallbackは行わない。

API Server広告のsourceは採用監査版
[`api_server.py`](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/gateway/platforms/api_server.py)
と[`api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/gateway/platforms/api_server_runs.py)。
これは現在の稼働版の保証ではない。live制限の解除には稼働commit、認証方式、安全なtest profileを確認し、
同じsessionの継続・要求ID・失効・ACK・結果不明をその版で検証する必要がある。

Phase 1のUI承認は、既存Hermes全外部操作を包むserver-side authorizationではない。
既存管理画面の操作はそのまま残る。Androidの詳細確認だけでサーバー全体を安全性認定しない。
API Serverへのlive write、SDUI/WebView、遠隔インストール、FCM、就活等の業務Mini Appは今回の範囲外。
解除にはそれぞれ契約・実装・認証/権限・安全な実環境試験の別承認が必要であり、manifestの読取成功では解除しない。

全体テスト・実行環境・未確認事項は [実装状態](IMPLEMENTATION_STATUS.md)、画面の入口は
[画面表](SCREEN_MAP.md) を参照する。
