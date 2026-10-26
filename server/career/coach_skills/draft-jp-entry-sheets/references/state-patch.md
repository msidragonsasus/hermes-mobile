# UNSAVED_JOBHUNT_PATCH_V4

GitHub write-throughに失敗した確定変更を、次のターンで再試行するための最小差分。成功済みと表現しない。

```json
{
  "patch_type": "UNSAVED_JOBHUNT_PATCH_V4",
  "schema_version": "4.1",
  "base_revision": 42,
  "operations": [
    {
      "op": "update_root_fields",
      "field": "last_session",
      "values": {
        "purpose": "exploration",
        "topic": "判断の理由",
        "next_question": "その方法を選んだ理由は？"
      }
    }
  ]
}
```

この例はlast_sessionのscalar fieldだけを更新し、既存taskとopen_questionsを変更しない。taskの更新は最新stateから全fieldを保持した完全なrecordをupsert_recordへ渡す。新規taskは最新stateで未使用と確認したIDを採番する。open_questionsの更新は既存の未解決項目を保持した完全な配列を構築し、変更のない場合はfield自体を省略する。

書込直前に最新stateとSHAを再取得する。base revisionが古ければ直接適用せず、ID単位のthree-way mergeを行う。異なるrecordまたは同じrecordの異なるfieldだけ自動mergeし、同じrecordの同じfieldが異なる場合はユーザー確認まで停止する。削除operationは標準で提供せず、status変更を使う。

`upsert_record`のrecordは完全な置換値。変更fieldだけを渡して既存の根拠や不採用理由を落とさない。`update_root_fields`はcandidateまたはlast_sessionのvaluesを子field単位で更新する。schema_version、revision、他のroot、削除は操作対象にしない。rootの異なるfieldはmergeでき、同じfieldの配列・入れ子の相違は競合にする。

訂正は旧factをSUPERSEDEDとし、本人の根拠を持つFACT_CORRECTION decision、任意の置換fact、依存するstory/draftの再確認指定を同じ更新に含める。Pythonのapply_patch/three_way_mergeを実行した経路では検証と再確認伝播を強制できる。通常Chatのconnector直書込でも同じ契約を確認するが、コード検証を実行していなければNOT_RUNとする。

patchへcontacts、secret、raw Chat、全文export、添付原本、backup、raw patch payload、第三者PII、未同意sensitive情報を入れない。

ローカルv3の`JOBHUNT_PATCH_V3`は互換経路として残る。共有lock、revision照合、preview、全体検証、backup、原子的交換、metadata-only auditを維持し、v4へdual-writeしない。

## schema 4.1の変更契約

再試行可能なJSON patchとして提示する場合は上記envelopeを使う。文章での変更候補はJSON patchと呼ばず、まだ適用できる形式でないことを区別する。

- fact: fact_id、classification（CONFIRMED / INFERRED / UNKNOWN / CONFLICTED / SUPERSEDED / SENSITIVE_REQUIRES_OPT_IN / SKIPPED_NOT_JOB_HUNTING）、claim。statusは旧入力の互換読込専用。
- provenance: source_kind、source_role、source_ref、source_date（YYYY-MM-DDまたはunknown）、imported_at_jst（YYYY-MM-DD HH:MM JST）、confidence（low / medium / high）。CONFIRMEDには本人発言か本人一次資料の根拠が必要。分類とprovenanceの意味はevidence-policyに従う。
- 参照は単一IDの文字列、複数は文字列配列。source_idsはSRC、fact_ids/verified_fact_ids/supporting_fact_ids/contradicting_fact_ids/review_fact_idsはFACT、story_idsはST、dependency_idsはTASK、decision_idsはDECを参照する。存在しないIDや別種のIDは使わない。
- story: story_idとfact_idsで素材の根拠を結び、本人・他者・場面を分ける。再確認中はneeds_review=trueと原因review_fact_idsを保持する。
- decision: decision_id、kind、scope、reason、本人根拠provenance。kindはFACT_CORRECTION / SCOPE_EXCLUSION / STYLE_PREFERENCE / TEMPORARY_POLICY / STRATEGY_CHOICE。statusはactive/retired（省略時active）。scopeはpurpose（exploration / strategy / creation / practice / operations / all）、question_type、application_id、company_idの必要なものを含むobject。
- FACT_CORRECTIONとSCOPE_EXCLUSIONは空でないtarget_idsを持つ。訂正の対象はFACTだけ。撤回事実は旧claimを保持してSUPERSEDEDにし、置換がある場合だけsuperseded_by_fact_idで新factへ関連づける。
- STYLE_PREFERENCEはpreferred/rejectedの対比例と理由を持つ。TEMPORARY_POLICYとSTRATEGY_CHOICEはreview_conditionを必須とする。設問上の不採用でfactの分類を変えない。
- 文書metadata: document_id、status、immutableなpath。version_evidenceはpathごとの配列で、各要素はclaim（その版で使った主要な本人主張）と空でないfact_idsを持つ。verified_fact_idsを持つ場合は現在版の対応と一致させる。本人主張がない文書だけ空配列にできる。既存版の対応を消したり書き換えたりしない。
- last_session: purpose、topic、answered_questions（questionとfact_ids、必要ならanswer_summary）、open_questions（問いの配列）、next_question、必要なdecision_ids。配列を更新するときは未解決分や既回答を不用意に落とさない。

表現を整えるだけでは確認待ちを解除しない。訂正後のfact参照・主張の支持・不採用範囲を再確認できた場合だけ、当該素材のneeds_reviewを解消する。未提出文書の内容変更も新しい版を使う。
