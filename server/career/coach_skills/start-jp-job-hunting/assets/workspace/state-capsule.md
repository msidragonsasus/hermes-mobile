# JOBHUNT_STATE_V3

未生成。次のコマンドで、ID、制御済みstatus、priority、日付、revisionだけを含む縮約状態を生成する。

```bash
python3 scripts/state_capsule.py .jobhunt --output .jobhunt/exports/state-capsule.md
```

profile自由記述、story title、企業名、program名、task title、連絡先、document本文、認証情報は含めない。ただし匿名化を保証しないため、外部共有前に内容を確認する。状態capsuleは正本ではなく、更新には`JOBHUNT_PATCH_V3`を使う。
