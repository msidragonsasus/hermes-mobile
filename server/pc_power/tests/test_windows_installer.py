"""Run the installer's file boundary in an NTFS sandbox, without host actions."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "nt", "Windows installer ACL integration")
class WindowsInstallerTests(unittest.TestCase):
    powershell = "powershell.exe"

    def run_installer(self, setup="", source_path=None, fail_acl_creation=False, trust_fixture_identity=True):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        installer = source_path or Path(__file__).parents[1] / "windows" / "Install-HermesPcPower.ps1"
        source = installer.read_text(encoding="utf-8-sig")
        source = source[:source.index("$taskName =")]
        self.assertNotIn("Register-ScheduledTask", source)
        self.assertNotIn("New-NetFirewallRule", source)
        self.assertNotIn("Start-ScheduledTask", source)
        source = source.replace(
            "if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))",
            "if ($false)",
        )
        if trust_fixture_identity:
            source = source.replace(
                "return @('S-1-5-18', 'S-1-5-32-544')",
                "return @('S-1-5-18', 'S-1-5-32-544', $fixtureSid)",
            )
        source = source.replace(
            "$trustedOwners = @(Get-HermesPcPowerTrustedSids) + @(",
            "$trustedOwners = @(Get-HermesPcPowerTrustedSids) + @($fixtureSid) + @(",
        )
        source = source.replace(
            "$security.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))",
            "$security.SetOwner([Security.Principal.SecurityIdentifier]::new($fixtureSid))",
        )
        source = source.replace(
            "[Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)",
            "$fixtureParent",
        )
        source = source.replace("$env:ProgramData", "$fixtureParent")
        source = source.replace("$directory = $directory.Parent", "$directory = $null")
        source = source.replace(
            "(Join-Path $PSScriptRoot 'HermesPcPower.ps1')",
            "(Join-Path $fixtureParent 'source.ps1')",
        )
        if fail_acl_creation:
            source = source.replace(
                "[IO.Directory]::CreateDirectory($Path, $security) | Out-Null",
                "throw 'fixture ACL creation failed'",
            )
            source = source.replace(
                "[IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($Path), $security)",
                "throw 'fixture ACL creation failed'",
            )
        for fixture_name in ("fixtureRoot", "junctionTarget", "targetParent"):
            setup = setup.replace(
                f"[IO.Directory]::CreateDirectory(${fixture_name}, (New-FixtureSecurity))",
                f"(New-FixtureDirectory ${fixture_name})",
            )
        fixture_path = str(root).replace("'", "''")
        self.assertNotIn("'@", source)
        harness = f"""$ErrorActionPreference = 'Stop'
$fixtureParent = '{fixture_path}'
$fixtureSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$fixtureRoot = Join-Path $fixtureParent 'HermesPcPower'
function New-FixtureSecurity {{
    $security = [Security.AccessControl.DirectorySecurity]::new()
    $security.SetOwner([Security.Principal.SecurityIdentifier]::new($fixtureSid))
    $security.SetAccessRuleProtection($true, $false)
    foreach ($principalSid in @('S-1-5-18', 'S-1-5-32-544', $fixtureSid)) {{
        $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($principalSid), 'FullControl',
            'ContainerInherit, ObjectInherit', 'None', 'Allow'))
    }}
    return $security
}}
function Add-UntrustedFixtureAccess([string]$Path, [string]$Rights = 'ReadAndExecute') {{
    $entry = Get-Item -LiteralPath $Path -Force
    $security = Get-FixtureAccess $entry
    $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        [Security.Principal.SecurityIdentifier]::new('S-1-1-0'), $Rights, 'Allow'))
    Set-FixtureAccess $entry $security
}}
function Get-FixtureAccess($Entry) {{
    if ($PSVersionTable.PSEdition -eq 'Core') {{
        return [IO.FileSystemAclExtensions]::GetAccessControl($Entry, [Security.AccessControl.AccessControlSections]::Access)
    }}
    return $Entry.GetAccessControl([Security.AccessControl.AccessControlSections]::Access)
}}
function Set-FixtureAccess($Entry, $Security) {{
    if ($PSVersionTable.PSEdition -eq 'Core') {{
        [IO.FileSystemAclExtensions]::SetAccessControl($Entry, $Security)
    }} else {{ $Entry.SetAccessControl($Security) }}
}}
function New-FixtureDirectory([string]$Path) {{
    if ($PSVersionTable.PSEdition -eq 'Core') {{
        [IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($Path), (New-FixtureSecurity))
        return [IO.DirectoryInfo]::new($Path)
    }}
    return [IO.Directory]::CreateDirectory($Path, (New-FixtureSecurity))
}}
function icacls.exe {{ $global:LASTEXITCODE = 5 }}
Set-FixtureAccess (Get-Item -LiteralPath $fixtureParent) (New-FixtureSecurity)
[IO.File]::WriteAllText((Join-Path $fixtureParent 'source.ps1'), 'fixture receiver')
{setup}
$installer = @'
{source}
'@
$boundaryReached = $false
$filesProtected = $false
$failure = $null
try {{
    & ([scriptblock]::Create($installer)) -ListenAddress '127.0.0.1' -AllowedClient '127.0.0.1'
    $boundaryReached = $true
    $filesProtected = $true
    foreach ($fixtureFile in Get-ChildItem -LiteralPath $fixtureRoot -File) {{
        $fileSecurity = Get-FixtureAccess $fixtureFile
        foreach ($rule in $fileSecurity.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {{
            if ($rule.AccessControlType -eq 'Allow' -and
                $rule.IdentityReference.Value -notin @('S-1-5-18', 'S-1-5-32-544', $fixtureSid)) {{
                $filesProtected = $false
            }}
        }}
    }}
}} catch {{ $failure = $_.Exception.Message }}
@{{ boundary_reached = $boundaryReached; files_protected = $filesProtected; failure = $failure }} | ConvertTo-Json -Compress
"""
        script = root / "fixture.ps1"
        script.write_text(harness, encoding="utf-8-sig")
        process_environment = os.environ.copy()
        if self.powershell == "powershell.exe":
            process_environment["PSModulePath"] = str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/Modules")
        result = subprocess.run(
            [self.powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script)],
            capture_output=True, text=True, timeout=30, env=process_environment,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        return root / "HermesPcPower", json.loads(result.stdout.strip())

    def test_new_install_creates_protected_directory_before_sensitive_files(self):
        root, result = self.run_installer()
        self.assertTrue(result["boundary_reached"], result)
        self.assertTrue(result["files_protected"], result)
        self.assertEqual("fixture receiver", (root / "HermesPcPower.ps1").read_text())
        self.assertGreaterEqual(len((root / "sleep-token").read_text()), 32)

    def test_trusted_reinstall_preserves_token_operations_and_backup(self):
        root, result = self.run_installer("""
[IO.Directory]::CreateDirectory($fixtureRoot, (New-FixtureSecurity)) | Out-Null
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'HermesPcPower.ps1'), 'old receiver')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'sleep-token'), 'existing-fixture-token')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'completed-operations.txt'), 'existing operation')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'completed-operations.txt.status.json'), 'existing status')
""")
        self.assertTrue(result["boundary_reached"], result)
        self.assertEqual("existing-fixture-token", (root / "sleep-token").read_text())
        self.assertEqual("existing operation", (root / "completed-operations.txt").read_text())
        self.assertEqual("existing status", (root / "completed-operations.txt.status.json").read_text())
        backups = list(root.glob("HermesPcPower.ps1.backup-*"))
        self.assertEqual(1, len(backups))
        self.assertEqual("old receiver", backups[0].read_text())

    def test_explicit_untrusted_root_ace_fails_before_writes(self):
        root, result = self.run_installer("""
[IO.Directory]::CreateDirectory($fixtureRoot, (New-FixtureSecurity)) | Out-Null
Add-UntrustedFixtureAccess $fixtureRoot
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'HermesPcPower.ps1'), 'untrusted receiver')
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'sleep-token'), 'attacker-known-fixture-token')
""")
        self.assertFalse(result["boundary_reached"], result)
        self.assertIn("untrusted access", result["failure"])
        self.assertEqual("untrusted receiver", (root / "HermesPcPower.ps1").read_text())
        self.assertEqual("attacker-known-fixture-token", (root / "sleep-token").read_text())
        self.assertEqual([], list(root.glob("*.backup-*")))

    def test_inherited_unprotected_directory_is_rejected(self):
        root, result = self.run_installer("New-Item -ItemType Directory -Path $fixtureRoot | Out-Null")
        self.assertFalse(result["boundary_reached"], result)
        self.assertIn("protected permissions", result["failure"])
        self.assertEqual([], list(root.iterdir()))

    def test_untrusted_owner_is_rejected_without_adopting_existing_token(self):
        root, result = self.run_installer("""
[IO.Directory]::CreateDirectory($fixtureRoot, (New-FixtureSecurity)) | Out-Null
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'sleep-token'), 'attacker-known-fixture-token')
""", trust_fixture_identity=False)
        self.assertFalse(result["boundary_reached"], result)
        self.assertIn("untrusted owner", result["failure"])
        self.assertEqual("attacker-known-fixture-token", (root / "sleep-token").read_text())
        self.assertFalse((root / "HermesPcPower.ps1").exists())

    def test_untrusted_parent_replacement_permissions_are_rejected(self):
        for rights in ("DeleteSubdirectoriesAndFiles", "Delete", "ChangePermissions", "TakeOwnership"):
            with self.subTest(rights=rights):
                root, result = self.run_installer(f"Add-UntrustedFixtureAccess $fixtureParent '{rights}'")
                self.assertFalse(result["boundary_reached"], result)
                self.assertIn("untrusted replacement", result["failure"])
                self.assertFalse(root.exists())

    def test_null_parent_dacl_is_rejected(self):
        root, result = self.run_installer("""
$parentSecurity = Get-FixtureAccess (Get-Item -LiteralPath $fixtureParent)
$parentSecurity.SetSecurityDescriptorSddlForm('D:NO_ACCESS_CONTROL', [Security.AccessControl.AccessControlSections]::Access)
Set-FixtureAccess (Get-Item -LiteralPath $fixtureParent) $parentSecurity
""")
        self.assertFalse(result["boundary_reached"], result)
        self.assertIn("null DACL", result["failure"])
        self.assertFalse(root.exists())

    def test_parent_junction_is_rejected_before_directory_creation(self):
        root, result = self.run_installer("""
$realFixtureParent = $fixtureParent
$targetParent = Join-Path $realFixtureParent 'parent-target'
[IO.Directory]::CreateDirectory($targetParent, (New-FixtureSecurity)) | Out-Null
$fixtureParent = Join-Path $realFixtureParent 'parent-link'
New-Item -ItemType Junction -Path $fixtureParent -Target $targetParent | Out-Null
""")
        self.assertFalse(result["boundary_reached"], result)
        self.assertIn("regular, trusted directory", result["failure"])
        self.assertFalse(root.exists())
        self.assertEqual([], list((root.parent / "parent-target").iterdir()))

    def test_untrusted_children_and_nested_backups_are_rejected(self):
        for relative_path in (
            "HermesPcPower.ps1", "sleep-token", "completed-operations.txt",
            "completed-operations.txt.status.json", "completed-operations.txt.status.json.tmp",
            "nested/old-backup.ps1",
        ):
            with self.subTest(path=relative_path):
                root, result = self.run_installer(f"""
[IO.Directory]::CreateDirectory($fixtureRoot, (New-FixtureSecurity)) | Out-Null
$fixtureChild = Join-Path $fixtureRoot '{relative_path}'
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($fixtureChild)) | Out-Null
[IO.File]::WriteAllText($fixtureChild, 'unchanged fixture')
Add-UntrustedFixtureAccess $fixtureChild
""")
                self.assertFalse(result["boundary_reached"], result)
                self.assertIn("untrusted access", result["failure"])
                self.assertEqual("unchanged fixture", (root / relative_path).read_text())
                self.assertEqual([], list(root.glob("*.backup-*")))

    def test_root_and_nested_junctions_are_rejected_without_following(self):
        for nested in (False, True):
            with self.subTest(nested=nested):
                junction_path = "(Join-Path $fixtureRoot 'nested')" if nested else "$fixtureRoot"
                root, result = self.run_installer(f"""
$junctionTarget = Join-Path $fixtureParent 'junction-target'
[IO.Directory]::CreateDirectory($junctionTarget, (New-FixtureSecurity)) | Out-Null
[IO.File]::WriteAllText((Join-Path $junctionTarget 'sleep-token'), 'unchanged junction target')
if ({'$true' if nested else '$false'}) {{
    [IO.Directory]::CreateDirectory($fixtureRoot, (New-FixtureSecurity)) | Out-Null
}}
New-Item -ItemType Junction -Path {junction_path} -Target $junctionTarget | Out-Null
""")
                self.assertFalse(result["boundary_reached"], result)
                self.assertIn("Reparse points", result["failure"])
                target = root.parent / "junction-target"
                self.assertEqual(["sleep-token"], [entry.name for entry in target.iterdir()])
                self.assertEqual("unchanged junction target", (target / "sleep-token").read_text())

    def test_acl_creation_failure_never_reaches_sensitive_writes(self):
        root, result = self.run_installer(fail_acl_creation=True)
        self.assertFalse(result["boundary_reached"], result)
        self.assertIn("ACL creation failed", result["failure"])
        self.assertFalse(root.exists())

    def test_production_policy_has_no_localized_principals_or_native_acl_command(self):
        source = (Path(__file__).parents[1] / "windows" / "Install-HermesPcPower.ps1").read_text(encoding="utf-8-sig")
        self.assertIn("return @('S-1-5-18', 'S-1-5-32-544')", source)
        self.assertNotIn("icacls.exe", source)
        self.assertLess(source.index("Initialize-HermesPcPowerDirectory -Path $installDirectory"), source.index("Copy-Item"))


@unittest.skipUnless(os.name == "nt" and shutil.which("pwsh"), "PowerShell Core installer ACL integration")
class CoreWindowsInstallerTests(WindowsInstallerTests):
    powershell = "pwsh"


if __name__ == "__main__":
    unittest.main()
