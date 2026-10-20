# Phase 1 protocol compatibility

更新: 2026-09-06。実稼働Hermesへの接続試験は未実施。採用監査sourceと合成fixtureに基づく。

| 機能 | 経路・証拠 | Phase 1の判定と制限 |
|---|---|---|
| Session一覧 | 既存Dashboard WebSocket `session.list` の `sessions[].id/title` | 固定scopeのread成功時だけSupported。saved IDでありruntime IDへ自動変換しない |
| 実行観測 | 既存parserのmessage/tool/approval/clarifyイベント | socket listenerでserver/profile/process/generationを固定。クライアントが観測した範囲だけで、永続server Run ID・全Run履歴は提供しない |
| イベント欠落 | 既存seq/replay、replay truncation、epoch変更 | Unknownとして扱う。切断を実行失敗へ変換しない |
| Dashboard ready | `skin/change_events/heartbeat/replay_epoch`等の限定handshake | 全機能の対応広告とは解釈しない |
| Dashboard read失敗 | RPC error code、timeout、scope不一致、壊れたDTO | method-not-foundはUnsupported、認証・不正応答は理由付きBlocked、接続未確定はUnknown |
| API Server discovery | `/v1/capabilities`専用DTOとoffline decoder | HTTP 401/403、404、timeout、壊れた応答を区別。Dashboard認証・DTOと共用しない。実HTTP接続は未実施 |
| API Server run/approval/idempotency | sourceが広告するfeatureと `runs_idempotency` | 広告値を保持するだけ。継続session、exact approval、idempotencyのlive結合検証なし。live writeはdisabled |
| Mini App実行 | Phase 1ではmanifest基盤のみ | 未実装renderer・遠隔配布・任意外部操作はdisabled |

新しいreadは既存socket、認証、request ID、pending解決・timeoutを利用する。
切断中やsocket送信拒否時にキューへ残さず、接続を自動作成しない。
scope切替後の結果を新しいprofileへ採用しない。未知endpointの推測や、送信後のprotocol fallbackは行わない。

API Server広告のsourceは採用監査版
[`api_server.py`](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/gateway/platforms/api_server.py)
と[`api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/9dd6634c5635321cf38840cc30e9b51226689128/gateway/platforms/api_server_runs.py)。
これは現在の稼働版の保証ではない。live制限の解除には稼働commit、認証方式、安全なtest profileを確認し、
同じsessionの継続・要求ID・失効・ACK・結果不明をその版で検証する必要がある。
