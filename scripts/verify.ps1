param(
    [string]$Python = $(if ($env:V2_PYTHON) { $env:V2_PYTHON } else { 'python' }),
    [switch]$LegacySuite,
    [string]$LegacyData
)
. (Join-Path $PSScriptRoot 'java-env.ps1')
$env:V2_PYTHON = $Python
function Invoke-CheckedPython([string[]]$Arguments) {
    & $Python @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Python check failed: $Arguments" }
}
Invoke-CheckedPython @('scripts/verify-inventory.py')
Invoke-CheckedPython @('scripts/check-contract-files.py')
Invoke-CheckedPython @('scripts/run-legacy-contracts.py')
Invoke-CheckedPython @('scripts/test-migration-preflight.py')
if ($LegacyData) {
    Invoke-CheckedPython @('scripts/migration-preflight.py','--source-data',$LegacyData,'--report','verification/migration-preflight.json')
}
$buildExit = & {
    # Windows PowerShell wraps native stderr as errors even for successful builds.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat checkFormat test bootJar --no-daemon --console=plain 2>&1 |
        ForEach-Object { $_.ToString() } |
        Out-File -FilePath verification/final-java-test.log -Encoding utf8
    $LASTEXITCODE
}
Get-Content -LiteralPath verification/final-java-test.log
if ($buildExit -ne 0) { throw 'Spring build/tests failed.' }
if ($LegacySuite) {
    Push-Location -LiteralPath (Join-Path $taskProjectRoot 'legacy-baseline')
    try {
        Invoke-CheckedPython @('-m','pytest','tests','-q','-p','no:cacheprovider','--junitxml=../verification/legacy-junit.xml')
    } finally { Pop-Location }
}
Write-Host 'v2 verification passed. Reports: verification/'
