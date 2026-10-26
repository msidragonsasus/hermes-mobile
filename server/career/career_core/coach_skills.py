"""Progressive, read-only access to the complete ported coaching playbooks."""
import json
from pathlib import Path

from .store import CareerError

ROOT = Path(__file__).resolve().parents[1] / "coach_skills"


def catalog():
    return json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))


def read_skill(name, resource="SKILL.md"):
    if name not in {entry["name"] for entry in catalog()}:
        raise CareerError("skill_missing", "対応する就活手順が見つかりません。")
    root = (ROOT / name).resolve()
    if resource == "HERMES.md":
        return {"skill": name, "resource": resource, "text": (ROOT / resource).read_text(encoding="utf-8")}
    path = (root / resource).resolve()
    if not path.is_relative_to(root) or path.suffix != ".md" or not path.is_file():
        raise CareerError("skill_resource_missing", "参照資料が見つかりません。")
    return {"skill": name, "resource": resource, "text": path.read_text(encoding="utf-8")}


def register_skills(ctx):
    for entry in catalog():
        ctx.register_skill(name=entry["name"], path=ROOT / entry["name"] / "SKILL.md",
                           description=entry["description"])


def system_policy():
    return (ROOT / "HERMES.md").read_text(encoding="utf-8") + "\n利用可能な就活Skill:\n" + json.dumps(catalog(), ensure_ascii=False)
