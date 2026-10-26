# JOBHUNT_STATE_V3 縮約状態

ファイル非対応環境で継続状態を持ち運ぶための最小形式。正本ではなく、匿名化を保証しない。

```yaml
JOBHUNT_STATE_V3:
  schema_version: "3.0"
  workspace_revision: 0
  as_of_jst: "YYYY-MM-DD HH:MM JST"
  timezone: "Asia/Tokyo"
  profile:
    graduation_year: ""
    free_text_omitted: true
  story_ids: []
  active_applications:
    - application_id: "APP-001"
      status: "preparing"
      priority: "P1"
      deadline_jst: "YYYY-MM-DD"
      deadline_precision: "date_only"
  next_tasks:
    - task_id: "TASK-001"
      application_id: "APP-001"
      status: "todo"
      priority: "P1"
      due_jst: ""
      due_precision: "unknown"
  source_freshness: []
  omitted_content: ["profile_free_text", "story_titles", "company_names", "program_names", "task_titles", "contacts", "document_bodies"]
  privacy_notice: "Reduced state only; not guaranteed anonymous. Review before sharing."
```

## 含めないもの

- profileのconstraints、preferences、open questions等の自由記述
- story title・本文
- 企業名、program名、task title
- 氏名、住所、個人メール、電話番号、第三者連絡先
- メール本文、認証情報、document本文

## 更新

- ID、許可されたstatus、priority、日付、deadline precisionだけを出力する。
- 不正形式や自由記述値は空欄または除外する。
- capsuleを直接「正本へ統合済み」と扱わない。
- 変更は `workspace_revision` を基に `JOBHUNT_PATCH_V3` を作り、preview・検証・適用する。
- 外部共有前に毎回内容を確認する。
