param([switch]$Reload, [int]$Port = 8000)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $projectPath
$venvPython = Join-Path $projectPath '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    python -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11 이상이 필요합니다.' }
    & $venvPython -m pip install -r requirements.lock
    if ($LASTEXITCODE -ne 0) { throw '의존성 설치에 실패했습니다.' }
    & $venvPython -m pip install --no-deps -e .
    if ($LASTEXITCODE -ne 0) { throw '프로젝트 설치에 실패했습니다.' }
}
$arguments = @('-m', 'uvicorn', 'app.main:app_factory', '--factory', '--host', '127.0.0.1', '--port', "$Port", '--no-access-log')
if ($Reload) { $arguments += '--reload' }
Write-Host "둘러방 API: http://127.0.0.1:$Port/docs"
& $venvPython @arguments

