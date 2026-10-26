# Local audit metadata

`JOBHUNT_PATCH_V3`やdocument version保存の適用監査を置くlocal-only directory。

監査にはrevision、timestamp、対象file／ID、changed field names、SHA-256等だけを保存する。raw patch payload、CSV row values、document body、free-text note、contacts、credentialsは保存しない。

このdirectoryはprivate-repo modeでもGitHub対象外。
