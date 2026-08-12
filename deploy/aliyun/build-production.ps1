param(
  [string]$AssetBase = 'https://static.yryhx.cn/'
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$FrontendDir = Join-Path $RepoRoot 'frontend'
$BackendDir = Join-Path $RepoRoot 'backend'

Push-Location $FrontendDir
try {
  $env:VITE_ASSET_BASE = $AssetBase
  pnpm install --frozen-lockfile
  pnpm test
  pnpm build
} finally {
  Remove-Item Env:VITE_ASSET_BASE -ErrorAction SilentlyContinue
  Pop-Location
}

Push-Location $BackendDir
try {
  mvn clean package
} finally {
  Pop-Location
}

$Jar = Get-ChildItem (Join-Path $BackendDir 'target') -Filter 'talent-platform-*.jar' |
  Where-Object { $_.Name -notlike '*.original' } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

if (-not $Jar) {
  throw '未找到生产 JAR'
}

Write-Host "JAR=$($Jar.FullName)"
Write-Host "STATIC_ASSETS=$(Join-Path $FrontendDir 'dist\assets')"
Write-Host "SHA256=$((Get-FileHash -Algorithm SHA256 $Jar.FullName).Hash)"
