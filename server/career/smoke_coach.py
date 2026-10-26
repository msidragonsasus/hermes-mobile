"""Opt-in real Hermes/OAuth acceptance probe, using synthetic data in a temporary DB."""
import argparse
import os
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Run real Hermes turns using the existing Codex subscription")
    parser.add_argument("--model")
    parser.add_argument("--reasoning")
    parser.add_argument("--second-model")
    parser.add_argument("--second-reasoning")
    args = parser.parse_args()
    if not args.live:
        parser.error("--live is required; the probe uses the existing Codex subscription")
    if bool(args.model) != bool(args.reasoning) or bool(args.second_model) != bool(args.second_reasoning):
        parser.error("Each model requires its explicit reasoning level")
    if args.second_model and (not args.model or args.model == args.second_model):
        parser.error("Use two distinct catalog model IDs for the two-model probe")
    # Process-local override. Never point this probe at the user's actual career database.
    with tempfile.TemporaryDirectory(prefix="hermes-career-smoke-") as directory:
        os.environ["CAREER_DATA_DIR"] = directory
        from career_core.coach_runtime import HermesCoachRuntime
        from career_core.coach_store import CoachStore
        from career_core.store import CareerError, Store
        store = Store(directory)
        coach = CoachStore(store)
        first_execution = ({"provider": "openai-codex", "model": args.model, "reasoning_effort": args.reasoning}
                           if args.model else None)
        second_execution = ({"provider": "openai-codex", "model": args.second_model,
                             "reasoning_effort": args.second_reasoning} if args.second_model else first_execution)

        def turn(text, operation, execution=first_execution, target=None):
            payload = {"text": text, "operation_id": operation}
            if execution:
                payload["execution"] = execution
            if target:
                payload["target"] = target
            coach.submit(payload, "synthetic")
            run = coach.claim()
            reply = HermesCoachRuntime(coach).run(run)
            checkpoint = coach.snapshot("synthetic")["checkpoint"]
            coach.finish(run["id"], "waiting_user" if checkpoint.get("waiting_user") else "completed",
                         {"artifact_ids": checkpoint.get("artifact_ids", [])}, reply=reply)
            completed = next(item for item in coach.snapshot("synthetic")["runs"] if item["id"] == run["id"])
            effective = completed["result"].get("effective_execution", {})
            assert effective.get("state") == "dispatched", "No model dispatch was observed"
            assert all(effective.get(key) == value for key, value in completed["execution"].items()), "Execution mismatch"
            print("DISPATCH_OK:", completed["execution"]["model"], completed["execution"]["reasoning_effort"])
            return run

        try:
            first = turn(
                "これは合成データによる就活Appの動作検証です。本人発言は「私はゼミの資料を整理し、確認しやすいように見出しを付けました。」です。"
                "この一文を本人事実として保存し、この材料だけで100字以内の自己PR下書きを作って保存してください。"
                "関連するSkill本文と証拠・質問規約を読み、文字数もツールで確認してください。", "smoke-create-1")
            entities = store.snapshot()["entities"]
            assert any(e["kind"] == "facts" for e in entities), "No fact saved"
            doc = next(e for e in entities if e["kind"] == "documents")
            original_path = doc["data"]["path"]
            assert len(doc["data"]["body"].replace("\n", "")) <= 100, "Draft exceeds requested count"
            events = coach.events("synthetic", 0)["events"]
            assert any(e["type"] == "skill" for e in events), "No skill actually read"
            assert coach.receipts(first["id"]), "No durable write receipt"
            coach = CoachStore(Store(directory))
            turn("続きから。訂正します。私は部活動の資料を整理し、確認しやすいように見出しを付けました。"
                 "ゼミという前の記録を訂正し、保存した自己PRも新しい版に修正してください。", "smoke-correct-2",
                 execution=second_execution, target={"kind": "documents", "id": doc["id"], "version": doc["data"]["version"]})
            entities = store.snapshot()["entities"]
            assert any(e["kind"] == "facts" and e["data"].get("status", e["data"].get("classification")) == "SUPERSEDED" for e in entities), "Correction did not supersede old evidence"
            with store.connect() as db:
                assert db.execute("SELECT 1 FROM document_files WHERE path=?", (original_path,)).fetchone(), "Old draft lost"
                assert db.execute("SELECT COUNT(*) FROM document_files").fetchone()[0] >= 2, "No revised version saved"
            turn("今日は文章を仕上げず考えたいです。さっきの経験で、自分がどんな判断をしたのか一問ずつ掘り下げてください。", "smoke-explore-3")
            assert coach.snapshot("synthetic")["checkpoint"].get("waiting_user") is True, "Exploration must wait for the user"
            print("PASS: real Hermes skill read, fact/draft save, durable continuation, correction, immutable versions, answer-wait state")
            print("Synthetic temporary data only. No GitHub sync or live user data was used.")
        except CareerError as error:
            print("NOT COMPLETED: " + error.code)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
