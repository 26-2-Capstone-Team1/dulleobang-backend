param([switch]$NativePostgres)
. (Join-Path $PSScriptRoot 'java-env.ps1')
$taskSecretsFile = Join-Path $taskProjectRoot '.runtime\dev-secrets.json'
if (-not (Test-Path -LiteralPath $taskSecretsFile)) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $taskSecretsFile) -Force | Out-Null
    $taskKeys = @{}
    foreach ($taskName in @('JWT_SECRET','WORKER_JWT_SECRET','STORAGE_SECRET')) {
        $taskBytes = New-Object byte[] 48
        $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
        $taskKeys[$taskName] = [Convert]::ToBase64String($taskBytes)
    }
    $taskKeys | ConvertTo-Json | Set-Content -LiteralPath $taskSecretsFile -Encoding UTF8
}
$taskSavedKeys = Get-Content -Raw -LiteralPath $taskSecretsFile | ConvertFrom-Json
foreach ($taskName in @('JWT_SECRET','WORKER_JWT_SECRET','STORAGE_SECRET')) {
    if (-not [Environment]::GetEnvironmentVariable($taskName,'Process')) {
        [Environment]::SetEnvironmentVariable($taskName,$taskSavedKeys.$taskName,'Process')
    }
}
if ($NativePostgres) {
    Write-Host 'Starting v2 backend on :8081 with a disposable native PostgreSQL database.'
    & .\gradlew.bat localRun --no-daemon
} else {
    Write-Host 'Starting v2 backend on :8081. Start the Compose PostgreSQL service first.'
    & .\gradlew.bat bootRun --no-daemon
}
if ($LASTEXITCODE -ne 0) { throw 'Server stopped with an error.' }
