"""Mail-only correction of legacy deadline-change labels; source text is untouched."""
import re
from .notice_policy import classify

# Keep the original broad match for finding what the legacy rule accepted.
CHANGE = re.compile(r"(?:締切|期限).{0,12}(?:変更|延長|短縮)")
NEGATED_TAIL = re.compile(
    r"^(?:すること|する|させていただく|の対応)?(?:は|も|が|を)?"
    r"(?:いたしかね|致しかね|いたしません|致しません|しません|しない|できません|できない|"
    r"行いません|行わない|なし|ありません|ございません)"
)


def correct_deadline_notice(subject, body, original):
    """Refusal to extend and changes to event dates are not changed submission deadlines."""
    if original != ("action", "期限変更の案内"):
        return original
    text = subject + "\n" + body
    matches = list(CHANGE.finditer(text))
    affirmative = [
        match for match in matches
        if not re.search(r"[。！？]", match[0]) and
        not NEGATED_TAIL.search(text[match.end():match.end() + 40].lstrip())
    ]
    if affirmative:
        return original
    # Reclassify only the display label. Evidence/deadline extraction still sees
    # the complete original mail; the user's records are never rewritten.
    for match in reversed(matches):
        text = text[:match.start()] + "期限について" + text[match.end():]
    title, _, remaining = text.partition("\n")
    return classify(title, remaining)
