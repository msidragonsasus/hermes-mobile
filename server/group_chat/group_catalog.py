"""Read existing Bot Mode groups from the same profile metadata as the clients.

No roster mutation, inferred new room, or fallback to every installed profile.
Only selected metadata is returned; profile secrets and room logs never leave here.
"""
import re

from group_service import GroupError


def host_profiles():
    import yaml
    from hermes_cli.profiles import get_profile_dir, list_profile_names
    rows = []
    for name in list_profile_names():
        path = get_profile_dir(name) / "profile.yaml"
        try:
            value = yaml.safe_load(path.read_text(encoding="utf-8")) if path.is_file() else {}
        except (OSError, ValueError, yaml.YAMLError):
            raise GroupError("group_catalog_unavailable", 503) from None
        if value is not None and not isinstance(value, dict):
            raise GroupError("group_catalog_unavailable", 503)
        rows.append({"name": name, "ui_meta": (value or {}).get("ui_meta", {})})
    return rows


def clean(value):
    return value.strip() if isinstance(value, str) else ""


class GroupCatalog:
    def __init__(self, profiles=host_profiles):
        self.profiles = profiles

    def records(self):
        profiles = self.profiles()
        roster, folded_roster, legacy, titles = set(), {}, {}, {}
        snapshot = {}
        for profile in profiles:
            name = profile["name"]
            roster.add(name)
            folded_roster.setdefault(name.casefold(), []).append(name)
            meta = profile.get("ui_meta") or {}
            if not isinstance(meta, dict):
                raise GroupError("group_catalog_unavailable", 503)
            bot = meta.get("hermes-bots") or {}
            if not isinstance(bot, dict):
                raise GroupError("group_catalog_unavailable", 503)
            title = clean(bot.get("title"))
            if title:
                titles.setdefault(title.casefold(), []).append(name)
            groups = bot.get("groups") or []
            if not isinstance(groups, list):
                raise GroupError("group_catalog_unavailable", 503)
            for group in [*groups, bot.get("group")]:
                if clean(group):
                    legacy.setdefault(clean(group), []).append(name)
            if name == "default":
                snapshot = meta.get("hermes-bots-groups") or {}
        if not isinstance(snapshot, dict) or not isinstance(snapshot.get("rooms", {}), dict):
            raise GroupError("group_catalog_unavailable", 503)
        deleted = snapshot.get("deleted") or {}
        if not isinstance(deleted, dict):
            raise GroupError("group_catalog_unavailable", 503)
        removed = {key.casefold() for key in deleted}
        records, claimed = [], set()

        def member(value):
            if isinstance(value, dict):
                if value.get("source") not in (None, "", "local"):
                    raise GroupError("group_member_unavailable", 422)
                value = value.get("name") or value.get("handle")
            name = clean(value)
            matches = ([name] if name in roster else
                       folded_roster.get(name.casefold(), titles.get(name.casefold(), [])))
            if len(matches) != 1 or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", matches[0]):
                raise GroupError("group_member_unavailable", 422)
            return matches[0]

        for key, room in snapshot.get("rooms", {}).items():
            if not isinstance(key, str) or not isinstance(room, dict):
                raise GroupError("group_catalog_unavailable", 503)
            name = clean(room.get("name")) or key.removeprefix("name:").removeprefix("id:")
            aliases = {key, name, clean(room.get("roomId")), clean(room.get("id")), "name:" + name}
            if clean(room.get("roomId")):
                aliases.add("id:" + clean(room["roomId"]))
            claimed.update(a.casefold() for a in aliases if a)
            if room.get("tombstone") is True or removed.intersection(a.casefold() for a in aliases):
                continue
            records.append(self.build(key, name, room, aliases, legacy, member))
        for name, members in legacy.items():
            if name.casefold() in claimed or {name.casefold(), "name:" + name.casefold()}.intersection(removed):
                continue
            records.append(self.build("name:" + name, name, {"members": members}, {name, "name:" + name}, legacy, member))
        return records

    @staticmethod
    def build(key, name, room, aliases, legacy, member):
        record = {"groupId": key, "groupName": name, "displayName": clean(room.get("displayName")) or name,
                  "members": [], "runnable": False, "aliases": {a.casefold() for a in aliases if a}}
        record["aliases"].add(record["displayName"].casefold())
        try:
            raw = room.get("members")
            # Older clients wrote [{"name": ""}] while retaining correct
            # per-profile group tags. Recover only that known empty-name shape
            # from explicit membership tags, never from the installed roster.
            legacy_empty = (isinstance(raw, list) and bool(raw)
                            and all(isinstance(value, dict) and set(value) == {"name"}
                                    and isinstance(value["name"], str) and not value["name"].strip()
                                    for value in raw))
            if raw is None or legacy_empty:
                raw = [m for group, members in legacy.items() if group.casefold() in record["aliases"] for m in members]
            if not isinstance(raw, list) or not raw:
                raise GroupError("invalid_members", 422)
            members = list(dict.fromkeys(member(value) for value in raw))
            order = room.get("speakingOrder") or []
            if not isinstance(order, list):
                raise GroupError("invalid_members", 422)
            ordered = [member(value) for value in order]
            if any(value not in members for value in ordered) or not 1 <= len(members) <= 20:
                raise GroupError("invalid_members", 422)
            members = list(dict.fromkeys([*ordered, *members]))
            summary = member(room["finalSummaryMember"]) if room.get("finalSummaryMember") else members[0]
            system = room.get("systemPrompt") or ""
            if summary not in members or not isinstance(system, str) or len(system) > 100000:
                raise GroupError("invalid_group_settings", 422)
            record.update(members=members, summaryMember=summary, systemPrompt=system, runnable=True)
        except GroupError as failure:
            record["error"] = failure.code
        return record

    def list(self):
        return [{k: value for k, value in row.items() if k not in {"aliases", "systemPrompt"}}
                for row in self.records()]

    def resolve(self, args):
        rows = self.records()
        group_id, name = clean(args.get("groupId")), clean(args.get("groupName"))
        matches = [row for row in rows if row["groupId"] == group_id] if group_id else [
            row for row in rows if name and name.casefold() in row["aliases"]]
        if not matches:
            raise GroupError("group_not_found", 404)
        if len(matches) != 1:
            raise GroupError("group_ambiguous", 409)
        row = matches[0]
        if name and name.casefold() not in row["aliases"]:
            raise GroupError("group_selection_conflict", 409)
        if not row["runnable"]:
            raise GroupError(row["error"], 422)
        if "members" in args and args["members"] != row["members"]:
            raise GroupError("group_members_mismatch", 409)
        return dict(args, **{key: row[key] for key in ("groupId", "groupName", "members", "summaryMember", "systemPrompt")})
