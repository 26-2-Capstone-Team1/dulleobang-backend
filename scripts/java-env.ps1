$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskJavaTmp = $env:V2_JAVA_TMP
if (-not $taskJavaTmp) {
    $taskJavaTmp = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'dulleobang-v2\tmp'
}
New-Item -ItemType Directory -Path $taskJavaTmp -Force | Out-Null
$taskJavaOptions = "-Djava.io.tmpdir=$taskJavaTmp -Djdk.net.unixdomain.tmpdir=$taskJavaTmp"
if ($env:JAVA_TOOL_OPTIONS -notlike "*-Djdk.net.unixdomain.tmpdir=*") {
    $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' ' + $taskJavaOptions).Trim())
}
Set-Location -LiteralPath $taskProjectRoot
