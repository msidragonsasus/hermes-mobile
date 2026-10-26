# GitHub Chat Storage Policy v4

通常Chatの永続状態は、書込対応GitHubプラグイン経由でprivate repositoryの`.jobhunt/state.json`へ保存する。ローカルshell、Python、git、`gh`、PC常時起動はChat modeの前提にしない。

## 能力検出

製品名や厳密なtool名ではなく、説明・入力・出力から次の同等能力を検出する。

- repository検索: `search_installed_repositories_streaming`、`search_repositories`相当
- repository情報・visibility確認: `get_repo`相当
- file読取とblob SHA取得: `fetch_file`相当
- file作成: `create_file`相当
- 現在SHAを条件にしたfile更新: `update_file`相当

状態を次のように区別する。

- `UNAVAILABLE`: GitHubプラグイン自体がない。
- `READ_ONLY`: 検索・読取はできるがcreate/update相当がない。
- `AUTH_REQUIRED`: 認証切れ。repository不存在と混同しない。
- `PERMISSION_DENIED`: connectorは認証済みだが対象repositoryが不可視または書込権限不足。
- `REPOSITORY_MISSING`: 能力・認証はあるが対象repositoryがない。
- `READY_PRIVATE`: private性、読取、書込能力を確認済み。

未接続時も元の依頼を進める。探索なら一問への返答待ちでよく、作成・実行の依頼なら安全な作業に着手する。保存不能は一度短く伝え、次の詳細手順は接続を求められた場合だけ表示する。

```text
GitHubへの永続保存がまだ利用できません。

1. ChatGPTの設定またはPlugins Directory／Appsを開く
2. repositoryファイルの作成・更新に対応したGitHubプラグインを有効化する
3. GitHubアカウントを接続する
4. private repositoryへのアクセスを許可する
5. 接続後、このChatに戻って「接続した」と送る

状態: GitHub未接続。このChat内でのみ作業し、変更は未保存です。
```

読取専用なら「GitHubの内容は参照できますが、create_file／update_file相当の操作がないため、状態を保存できません」と明示する。認証切れは再接続、権限不足は対象repositoryへのアクセス許可を案内する。保存できていない状態を保存済みと表現しない。

## Repository discovery

次の順で、必要な候補だけを探索する。全private repositoryを無差別に読まない。

1. 今回ユーザーが明示した`owner/repository`
2. 現在の会話・利用可能なコンテクストの明示slug
3. 名前が完全一致する`jp-job-hunting-memory`
4. descriptionまたはmarkerが`jp-job-hunting-coach-state`
5. 対象repositoryへの`github_state_read`同意後だけ、`.jobhunt/manifest.json`を持つ候補を確認する

候補が複数ならrepository名だけを列挙して選択を求める。存在しない場合、作成toolがなければGitHub上で`jp-job-hunting-memory`をprivateとして手動作成するよう案内し、作成済みと装わない。

## Private verification and load

repository名からprivate性を推測しない。GitHub側の現在情報で`private`を確認できない、publicである、または途中でvisibility変更を検出した場合は読取・書込を停止する。対象repositoryへの`github_state_read`の明示同意を確認してから、private確認後に`.jobhunt/manifest.json`と`.jobhunt/state.json`を読む。書込・移行・接続済みであることから読取同意を推定しない。schema、revision、ID、参照、秘密情報、禁止fieldを確認し、stateのblob SHAをChat内に保持する。ユーザーには一度だけ`状態: GitHub r42を読込済み`のように示す。

4.1の根拠・訂正・再開契約はstate-managementとevidence-policyに従う。4.0は読込時に原本を変えず移行候補として解釈し、差分承認前に4.1で上書きしない。Pythonを実行できた場合だけ構造検証をコード実行済みとする。実行できない通常Chatでも、指示による確認と同意済み保存を維持する。コード検証・意味内容の確認・実保存結果は別の観測である。

## Consent and setup

読取、write-through、ChatGPTコンテクスト取込、sensitive note、personal contactを別同意として扱う。repository初期化前にmanifestと初期stateをpreviewし、次を一回限りで確認する。

- 対象private repositoryのmanifest/stateを読む（`github_state_read`。読取前に取得し、取得済みなら再確認しない）
- このprivate repositoryを就活状態の正本として使用する
- 確認済みの就活状態をこのコーチがwrite-throughする
- ChatGPTの既存コンテクスト移行候補を作る

外部toolが確認UIを出す場合も尊重する。初回同意後は、確定変更ごとに毎回「sync」と言わせない。削除、sensitive保存、大規模移行は別の明示確認が必要。

初期化完了後は一度だけ次を表示する。

```text
GitHubへの状態保存を有効化しました。

ChatGPTが現在参照できる就活関連メモリから、初期状態の移行候補を作れます。
ただし、現在このChatに提供されている範囲だけであり、全過去チャットの完全取得ではありません。

今ある範囲から始める
Memory Summaryも追加する
ChatGPTデータエクスポートを使う
今回は移行しない
```

「今ある範囲から」と返された場合、追加質問だけで止めず、その場で候補previewを作る。

## SHA-safe write-through

1. 書込直前にprivate性の現在確認、最新stateとblob SHAの再取得を行う。
2. Chatで保持したrevisionと最新revisionを比較する。
3. ID単位の`UNSAVED_JOBHUNT_PATCH_V4`を作る。candidate/last_sessionは専用root更新で扱い、変更を落とさない。訂正・関連素材のneeds_reviewを同じ更新に含める。
4. 競合がなければ最新revision+1とし、取得したSHAを条件にupdateする。
5. SHA不一致なら上書きせず再取得し、異なるrecordまたは異なるfieldだけ自動mergeする。
6. 同じrecordの同じfield、またはcandidate/last_sessionの同じ子fieldが異なる値ならユーザーへ確認する。配列・入れ子はそのfieldの一単位とし、一部だけ成功扱いしない。
7. 成功時だけcommit SHA、新revision、保存対象を報告し、Chat内のSHA/revisionを更新する。

本人の直接発言・訂正、応募状態、公式確認済み締切、task、選考観測、本人の意思決定、確認済み就活軸、対話の再開checkpointはwrite-through対象。本人が採用・保存を望んだ解釈はhypothesesとして保存し、本人事実へ昇格させない。推測した適性・志望度・不合格理由の自動保存、未確認締切の確定保存、未同意の途中案・sensitive情報・第三者連絡先・生チャットの保存は行わない。

## Immutable documents

ES等は`.jobhunt/documents/DOC-027-v004.md`のような新規版を先にcreateし、成功後だけstate metadata/current versionを更新する。同名pathがあれば上書きせず最新stateを再取得する。主要な本人主張とfact_idsの対応は、immutable pathをkeyにしたversion_evidenceへ保存し、既存版の対応も保持する。参照の存在と主張の支持は別に確認する。submitted文書を直すために旧本文・版・根拠を書き換えず、別documentを作る。

state更新失敗時は旧版を有効なまま保ち、新規fileをorphanとして報告する。ただし旧版が訂正により無効な素材を含む場合は提出候補として再使用しない。通常運用で`delete_file`を自動実行しない。

## Failure and unsaved patch

書込失敗は成功扱いせず、次を返す。

```text
保存状態: 未保存
理由: 権限不足／競合／通信失敗／private性未確認
再試行可能な差分: UNSAVED_JOBHUNT_PATCH_V4
```

patchにはbase revision、ID、変更fieldと必要最小限の値だけを含める。秘密情報・第三者PII・raw本文を含めない。

upsert_recordはrecord全体の置換なので、既存fieldを落とさない完全なrecordを使う。通信結果が不明なら最新state/文書を再取得し、予定した差分・revision・SHAと照合する。未確認のまま成功を伝えたり、同じcreateを盲目的に繰り返したりしない。保存済みと報告するには成功応答または実際に保存された内容の照合が必要。コード検証は保存できた証拠の代わりにならない。

## Chat mode / Local mode

- Chat mode: GitHub connectorで読込、write-through、checkpoint、復旧、接続診断を行う。v4 stateが唯一の構造化正本。
- Local/Codex mode: v3 workspace、Python、`github_sync.py`によるcommit/push/fast-forwardを移行、検証、一括処理、backupの互換経路として使う。v3とv4を自動dual-writeしない。
