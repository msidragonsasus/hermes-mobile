# Quick Start

## A. 仕様だけ読む

ZIPを展開し、まず `README.md` と `00_DECISION.md` を読む。

## B. Codex用リポジトリを作る例

```bash
git clone --branch dev https://github.com/msidragonsasus/hermes-mobile.git personal-ai-shell
cd personal-ai-shell
git checkout dev
git status --short
git rev-parse HEAD
git checkout -b docs/personal-ai-audit
```

`origin` はユーザーfork。既存checkoutがあれば新規cloneせず、そのroot/remote/HEAD/差分を確認する。
Git外の親フォルダや`.audit`を作業場所にせず、旧5c8ab7eへresetしない。
upstream参照先は `Hy4ri/hermes-mobile`。必要になった工程でremote設定を確認する。

## C. 仕様をリポジトリへ入れる

展開した `personal-ai-shell-spec` を、対象リポジトリ内の
次の場所へコピーする。

```text
docs/personal-ai-spec/
```

例:

```bash
mkdir -p docs/personal-ai-spec
cp -R /path/to/personal-ai-shell-spec/. docs/personal-ai-spec/
```

## D. Codexに渡す

対象リポジトリのルートでCodexを開き、
次のファイルの全文を最初の依頼として渡す。

```text
docs/personal-ai-spec/codex/CODEX_START_HERE.md
```

Codexが監査を終えるまでは、
`CODEX_01_FOUNDATION_VERTICAL_SLICE.md` を実行させない。

## E. 監査後

生成された次を確認する。

```text
docs/personal-ai/AUDIT_REPORT.md
docs/personal-ai/IMPLEMENTATION_PLAN.md
docs/personal-ai/RISK_REGISTER.md
```

採用済み監査がある場合はHEAD差分から必要な確認だけを行う。
次は実装計画のPR 00（文書のみ）を確認し、個別のコードPRを明示して依頼する。
以下はPhase 1全体の参照で、読むだけでは着手許可にならない。

```text
docs/personal-ai-spec/codex/CODEX_01_FOUNDATION_VERTICAL_SLICE.md
```
