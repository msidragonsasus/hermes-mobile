# Local/Codex GitHub Record and Fast-forward Policy

このreferenceはローカルv3互換経路専用である。通常Chatのv4読込・write-throughは`github-chat-storage.md`を使う。両者を自動dual-writeしない。

GitHub操作は外部への永続書込またはremote状態の取得であり、通常の就活相談や状態更新から暗黙実行しない。

## 必須条件

- 本人が今回GitHubへの記録、push、または取得を明示している。
- workspaceは`private-repo`モードで、外部repository操作への同意が記録されている。
- 対象remoteがcredentialを埋め込んでいない`github.com`のHTTPSまたはSSH URLである。
- repositoryがprivateであることをGitHub側の現在情報で確認する。確認不能、public、またはvisibility変更時は取得・pushを停止し、名前や過去の確認で代用しない。

## 最小公開

記録対象はallowlist内の就活状態に限定する。次は常に対象外。

- `contacts.csv`
- `patches/`
- `raw/`, `exports/`, `backups/`, `snapshots/`
- lock file、transaction staging、rollback path
- token、cookie、password、private key等

`.gitignore`だけを安全境界にせず、stage pathもallowlistで制限する。対象外パスが現在追跡中、または到達可能なlocal／remote Git履歴に一度でも存在する場合は停止する。削除済みファイルも安全とは扱わない。

## 副作用境界

- dry-run、local commit、push、pull-onlyを区別する。
- 読取依頼はcommit/push許可ではない。
- commit依頼はpush許可ではない。
- 既にstageされた別変更を同じcommitへ混ぜない。
- push前にremote historyをfetchし、remote-aheadまたはdivergedなら停止する。
- v0.8.0等の旧raw patchが履歴にある場合は、新しいprivate repositoryまたは適切な履歴書換えまで停止する。
- push失敗時はlocal commitが残ったかを明示する。
- public repositoryには記録しない。

## fast-forward取得

`--pull-only`は完全にcleanなcheckoutだけをfetchし、remoteへfast-forwardまたはunborn branchのrestoreを行う。分岐履歴は自動mergeしない。取得後にworkspace全体を検証する。

GitHub対象外の`contacts.csv`は空のschemaだけをlocal再生成できる。連絡先の値、patch監査、raw、export、backup、snapshotはGitHubから復元しない。
