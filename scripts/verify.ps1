param([string]$Python = $(if ($env:V2_PYTHON) { $env:V2_PYTHON } else { 'python' }), [switch]$LegacySuite)
. (Join-Path $PSScriptRoot 'java-env.ps1')
function Invoke-CheckedPython([string[]]$Arguments) {
    & $Python @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Python check failed: $Arguments" }
}
Invoke-CheckedPython @('scripts/verify-inventory.py')
Invoke-CheckedPython @('scripts/check-contract-files.py')
Invoke-CheckedPython @('scripts/run-legacy-contracts.py')
Invoke-CheckedPython @('scripts/test-migration-preflight.py')
$legacyData = Join-Path (Split-Path $taskProjectRoot -Parent) 'dulleobang-backend/data'
Invoke-CheckedPython @('scripts/migration-preflight.py','--source-data',$legacyData,'--report','verification/migration-preflight.json')
& .\gradlew.bat checkFormat test bootJar --no-daemon --console=plain 2>&1 | Out-File -FilePath verification/final-java-test.log -Encoding utf8
Get-Content -LiteralPath verification/final-java-test.log
if ($LASTEXITCODE -ne 0) { throw 'Spring build/tests failed.' }
if ($LegacySuite) {
    Push-Location -LiteralPath (Join-Path $taskProjectRoot 'legacy-baseline')
    try {
        Invoke-CheckedPython @('-m','pytest','tests','-q','-p','no:cacheprovider','--junitxml=../verification/legacy-junit.xml')
    } finally { Pop-Location }
}
Invoke-CheckedPython @('scripts/verify-original.py')
Invoke-CheckedPython @('scripts/final-review.py')
Write-Host 'v2 verification passed. Reports: verification/'
