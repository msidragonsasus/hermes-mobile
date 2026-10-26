# 継続状態の扱い v4

## 記録の正本と本人事実

GitHubを記録の正本とする。主張の扱いは保存場所の順位ではなく、本人の明示訂正・根拠の生成主体・時点・参照可能性で判断する。利用不能なGitHub、ファイル、Memory、Project、メール、カレンダーを読んだ・更新したと扱わない。

## Chat mode v4

`.jobhunt/manifest.json`は静的設定、`.jobhunt/state.json`は唯一の構造化正本、`.jobhunt/documents/`は追記型文書版である。stateは`schema_version=4.1`、正の`revision`、JST更新日時、candidate、facts、hypotheses、stories、target_companies、applications、tasks、documents、selection_events、decisions、sources、imports、last_sessionを持つ。collection・ID・保存先は4.0と同じ。

各Chatの最初の状態依存処理で`github-chat-storage.md`に従いconnector bootstrap、private確認、manifest/state読込、検証、blob SHA保持を行う。更新後だけ新しいSHA/revisionへ置換する。

確定変更は同意済みwrite-throughでSHA条件付き更新する。仮説を本人事実へ昇格させず、未確認締切を確定値にせず、削除より`cancelled / withdrawn / SUPERSEDED / retired`を優先する。

## 新Chatでの再開

初回の状態依存処理で現在の依頼に必要な記録だけを読む。`last_session`のpurpose、topic、answered_questions（questionとfact_ids、必要最小限のanswer_summary）、open_questions、next_questionを復元する。保留理由、設問別不採用、文体の対比例、見直し条件は`decisions`へ記録し、last_sessionからdecision_idsで参照できる。

今回の依頼が明確なら、その目的へ切り替える。回答済みの基礎質問を繰り返さず、最新訂正に関係する点だけ再確認する。過去の次問が解決済み・不要なら、その理由に沿って次問を変える。保存できていない記録を新Chatに引き継げると約束しない。

`candidate`はプロフィールや選択条件の要約であり、根拠のない本人実績を認定する欄ではない。事実はfactへ、解釈はhypothesisへ分ける。本人が採用・保存を望んだ解釈はhypothesisのまま保存できる。論点・回答済み質問・次問は再開用checkpointであり、未確認の本人事実として保存しない。

本人事実の確認・訂正を保存差分にする場合、candidateの要約更新だけで終えず、今回の本人発言を根拠とするfactも同じ差分に含める。対応する旧factがあれば訂正手順でSUPERSEDEDにする。旧値がcandidateにしかない場合は、旧値の根拠を推測してCONFIRMEDを作らず、今回確認したfactを新規IDで残す。

## 訂正・素材選択

保存前と再取得後に、SUPERSEDED、CONFLICTED、UNKNOWN、needs_review、適用範囲付き不採用を確認する。storyのfact_ids、文書のverified_fact_ids、版別version_evidenceから依存をたどる。訂正と再確認指定は同じstate更新へ含める。参照のない旧story・文書を根拠付き素材として扱わない。

submitted文書は当時の提出物として読むことはできるが、その表現を現在の本人事実に戻さない。訂正後に作る文書は再確認済みの素材で新規版とし、提出済み文書自体を変更しない。

## 実行境界と互換読込

Pythonを実行できる場合は同梱のvalidate_state_v4、merge_state_v4、coaching_state、migrate_state_v40を使える。構造検証・素材除外の実行結果と、主張を支持する意味内容の確認を区別する。通常Chatでこれらを実行できなくても、同意済みのconnector保存を全面停止しない。同じ契約を確認し、未実行のコード検証はNOT_RUNと記録する。

4.0は非破壊で互換読込し、4.1への移行差分を提示して承認後に保存する。旧statusは有効な場合だけclassificationへ正規化する。根拠不明の旧CONFIRMEDはUNKNOWN/needs_reviewとし、旧分類と理由はlegacy_validationに残す。診断欄を事実の根拠に戻さない。不正参照など未解決エラーがある場合は移行保存を保留し、確認できた範囲で相談を続ける。manifestの更新失敗時も、既に保存されたstateを巻き戻さず設定未完了として復旧する。

## Local/Codex compatibility v3

既存v3 workspace、`JOBHUNT_PATCH_V3`、共有lock、revision、backup、原子的交換、Git履歴の危険path検出は移行・検証・一括処理・backup用に維持する。v3を直接破壊せず、`migrate_workspace_v3_to_v4.py`で別出力へ変換する。v3とv4を同時に自動更新しない。

Local modeの連絡先、raw、patches、exports、backups、snapshotsはlocal-onlyであり、v4 GitHub stateへ含めない。
