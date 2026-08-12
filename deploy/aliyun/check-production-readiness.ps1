param(
  [string]$Domain = 'yryhx.cn',
  [string]$StaticDomain = 'static.yryhx.cn',
  [string]$EcsIp = '139.224.51.21',
  [string]$PublicBucket = 'yryhx-talent-public-cn-shanghai',
  [string]$StaticAssetPath = $env:TALENT_STATIC_ASSET_PATH,
  [string]$SshKey = $env:ALIYUN_ECS_SSH_KEY
)

$ErrorActionPreference = 'Stop'
$result = [ordered]@{}

function Invoke-HttpProbe([string]$Uri, [ValidateSet('GET', 'HEAD')][string]$Method = 'GET') {
  $handler = [System.Net.Http.HttpClientHandler]::new()
  $handler.AllowAutoRedirect = $false
  $handler.UseProxy = $false
  $client = [System.Net.Http.HttpClient]::new($handler)
  $client.Timeout = [TimeSpan]::FromSeconds(15)
  $httpMethod = if ($Method -eq 'HEAD') { [System.Net.Http.HttpMethod]::Head } else { [System.Net.Http.HttpMethod]::Get }
  $request = [System.Net.Http.HttpRequestMessage]::new($httpMethod, $Uri)
  try {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $headers = @{}
    foreach ($header in $response.Headers) {
      $headers[$header.Key] = ($header.Value -join ', ')
    }
    foreach ($header in $response.Content.Headers) {
      $headers[$header.Key] = ($header.Value -join ', ')
    }
    $body = if ($Method -eq 'GET') {
      $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } else {
      ''
    }
    return [pscustomobject]@{
      statusCode = [int]$response.StatusCode
      headers = $headers
      body = $body
      error = $null
    }
  } catch {
    return [pscustomobject]@{
      statusCode = 0
      headers = @{}
      body = ''
      error = $_.Exception.Message
    }
  } finally {
    $request.Dispose()
    $client.Dispose()
    $handler.Dispose()
  }
}

function New-HttpNotReady([string]$Reason) {
  return [pscustomobject]@{
    statusCode = 0
    headers = @{}
    body = ''
    error = $Reason
  }
}

function New-TlsNotReady([string]$Reason) {
  return [pscustomobject]@{
    subject = $null
    issuer = $null
    notAfter = $null
    daysRemaining = -1
    error = $Reason
  }
}

function Get-TlsInfo([string]$Name) {
  $tcp = [System.Net.Sockets.TcpClient]::new()
  try {
    $connect = $tcp.ConnectAsync($Name, 443)
    if (-not $connect.Wait([TimeSpan]::FromSeconds(15))) {
      throw "TLS connection timed out: $Name"
    }
    $ssl = [System.Net.Security.SslStream]::new($tcp.GetStream(), $false)
    try {
      $ssl.AuthenticateAsClient($Name)
      $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($ssl.RemoteCertificate)
      return [pscustomobject]@{
        subject = $certificate.Subject
        issuer = $certificate.Issuer
        notAfter = $certificate.NotAfter.ToUniversalTime().ToString('o')
        daysRemaining = [math]::Floor(($certificate.NotAfter.ToUniversalTime() - [datetime]::UtcNow).TotalDays)
        error = $null
      }
    } finally {
      $ssl.Dispose()
    }
  } catch {
    return [pscustomobject]@{
      subject = $null
      issuer = $null
      notAfter = $null
      daysRemaining = -1
      error = $_.Exception.Message
    }
  } finally {
    $tcp.Dispose()
  }
}

function Resolve-OptionalRecord([string]$Name, [string]$Type) {
  try {
    return @(Resolve-DnsName $Name -Type $Type -ErrorAction Stop |
      Where-Object { $_.Type -eq $Type } |
      ForEach-Object {
        if ($Type -eq 'A') { $_.IPAddress } else { $_.NameHost }
      })
  } catch {
    return @()
  }
}

$result.rootA = @(Resolve-OptionalRecord $Domain 'A')
$result.wwwCname = @(Resolve-OptionalRecord "www.$Domain" 'CNAME')
$result.staticCname = @(Resolve-OptionalRecord $StaticDomain 'CNAME')
$result.rootHttp = if ($result.rootA.Count -gt 0) { Invoke-HttpProbe "http://$Domain/" } else { New-HttpNotReady 'Root A record is not ready' }
$result.rootHttpsHealth = if ($result.rootA.Count -gt 0) { Invoke-HttpProbe "https://$Domain/actuator/health" } else { New-HttpNotReady 'Root A record is not ready' }
$result.wwwHttps = if ($result.wwwCname.Count -gt 0) { Invoke-HttpProbe "https://www.$Domain/" } else { New-HttpNotReady 'www CNAME is not ready' }
$result.rootTls = if ($result.rootA.Count -gt 0) { Get-TlsInfo $Domain } else { New-TlsNotReady 'Root A record is not ready' }
$result.wwwTls = if ($result.wwwCname.Count -gt 0) { Get-TlsInfo "www.$Domain" } else { New-TlsNotReady 'www CNAME is not ready' }

$normalizedAssetPath = if ([string]::IsNullOrWhiteSpace($StaticAssetPath)) {
  $null
} else {
  $StaticAssetPath.TrimStart('/')
}
if ($null -eq $normalizedAssetPath) {
  $result.staticAsset = New-HttpNotReady 'TALENT_STATIC_ASSET_PATH is not set'
  $result.publicOssAnonymous = New-HttpNotReady 'TALENT_STATIC_ASSET_PATH is not set'
} elseif ($result.staticCname.Count -eq 0) {
  $result.staticAsset = New-HttpNotReady 'Static CNAME is not ready'
  $result.publicOssAnonymous = New-HttpNotReady 'Static CNAME is not ready'
} else {
  $result.staticAsset = Invoke-HttpProbe "https://$StaticDomain/$normalizedAssetPath" 'HEAD'
  $result.publicOssAnonymous = Invoke-HttpProbe "https://$PublicBucket.oss-cn-shanghai.aliyuncs.com/$normalizedAssetPath" 'HEAD'
}
$result.staticTls = if ($result.staticCname.Count -gt 0) { Get-TlsInfo $StaticDomain } else { New-TlsNotReady 'Static CNAME is not ready' }

$candidateKeys = if (-not [string]::IsNullOrWhiteSpace($SshKey)) {
  @($SshKey)
} else {
  @(Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE 'Downloads') -Filter '*.pem' -File |
    Select-Object -ExpandProperty FullName)
}
$candidateKeys = @($candidateKeys)
if ($candidateKeys.Count -eq 0) {
  throw 'No SSH key was supplied and no .pem file was found in Downloads.'
}
foreach ($candidateKey in $candidateKeys) {
  if (-not (Test-Path -LiteralPath $candidateKey)) {
    throw "SSH key does not exist: $candidateKey"
  }
}

$remote = @'
set -eu
token=$(curl -fsS -X PUT -H 'X-aliyun-ecs-metadata-token-ttl-seconds: 60' http://100.100.100.200/latest/api/token 2>/dev/null || true)
role=$(curl -fsS -H "X-aliyun-ecs-metadata-token: $token" http://100.100.100.200/latest/meta-data/ram/security-credentials/ 2>/dev/null || true)
cd /opt/talent-platform
storage_type=$(grep '^STORAGE_TYPE=' .env | cut -d= -f2-)
private_bucket=$(grep '^OSS_PRIVATE_BUCKET=' .env | cut -d= -f2- || true)
public_bucket=$(grep '^OSS_PUBLIC_BUCKET=' .env | cut -d= -f2- || true)
public_endpoint=$(grep '^OSS_PUBLIC_ENDPOINT=' .env | cut -d= -f2- || true)
cdn_base=$(grep '^CDN_BASE_URL=' .env | cut -d= -f2- || true)
nginx_https_active=false
if [ -f nginx-https.conf ] && cmp -s nginx.conf nginx-https.conf \
  && [ -f certs/yryhx.cn/fullchain.pem ] && [ -f certs/yryhx.cn/privkey.pem ] \
  && [ -f certs/www.yryhx.cn/fullchain.pem ] && [ -f certs/www.yryhx.cn/privkey.pem ]; then
  nginx_https_active=true
fi
app_health=false
if curl -fsS http://127.0.0.1/actuator/health 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
  app_health=true
fi
printf '{"ramRole":"%s","storageType":"%s","privateBucketSet":%s,"publicBucketSet":%s,"publicEndpointSet":%s,"cdnBase":"%s","nginxHttpsActive":%s,"appHealth":%s}' \
  "$role" "$storage_type" \
  "$([ -n "$private_bucket" ] && echo true || echo false)" \
  "$([ -n "$public_bucket" ] && echo true || echo false)" \
  "$([ -n "$public_endpoint" ] && echo true || echo false)" \
  "$cdn_base" "$nginx_https_active" "$app_health"
'@
$remote = $remote -replace "`r", ''
$sshTempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$sshTempRoot = [System.IO.Path]::GetFullPath((Join-Path $sshTempBase ("talent-readiness-{0}" -f [guid]::NewGuid().ToString('N'))))
if (-not $sshTempRoot.StartsWith($sshTempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw 'Unsafe temporary SSH directory.'
}
New-Item -ItemType Directory -Path $sshTempRoot | Out-Null
$remoteJson = $null
try {
  for ($index = 0; $index -lt $candidateKeys.Count; $index++) {
    $candidateKey = $candidateKeys[$index]
    $restrictedKey = Join-Path $sshTempRoot ("candidate-{0}.pem" -f $index)
    Copy-Item -LiteralPath $candidateKey -Destination $restrictedKey
    & icacls.exe $restrictedKey /inheritance:r /grant:r "$env:USERNAME`:R" | Out-Null

    $encodedRemote = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($remote -replace "`r", '')))
    $candidateJson = ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -i $restrictedKey "ecs-user@$EcsIp" "echo '$encodedRemote' | base64 -d | sudo bash" 2>$null
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($candidateJson)) {
      $remoteJson = $candidateJson
      break
    }
  }
} finally {
  Get-ChildItem -LiteralPath $sshTempRoot -File -ErrorAction SilentlyContinue | ForEach-Object {
    & icacls.exe $_.FullName /inheritance:e /grant:r "$env:USERNAME`:(F)" | Out-Null
  }
  if ((Test-Path -LiteralPath $sshTempRoot) -and $sshTempRoot.StartsWith($sshTempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
    Remove-Item -LiteralPath $sshTempRoot -Recurse -Force
  }
}
if ([string]::IsNullOrWhiteSpace($remoteJson)) {
  throw 'Unable to connect to ECS with the supplied or discovered SSH keys.'
}
$result.ecs = $remoteJson | ConvertFrom-Json

$healthUp = $false
try {
  $healthUp = (($result.rootHttpsHealth.body | ConvertFrom-Json).status -eq 'UP')
} catch {
  $healthUp = $false
}
$rootRedirectReady = $result.rootHttp.statusCode -in @(301, 308) -and
  $result.rootHttp.headers.Location -like "https://$Domain/*"
$wwwRedirectReady = $result.wwwHttps.statusCode -in @(301, 308) -and
  $result.wwwHttps.headers.Location -like "https://$Domain/*"
$wwwCnameReady = @($result.wwwCname | ForEach-Object { $_.TrimEnd('.') }) -contains $Domain.TrimEnd('.')
$staticCnameReady = $result.staticCname.Count -gt 0
$staticCacheControl = [string]$result.staticAsset.headers.'Cache-Control'
$staticContentType = [string]$result.staticAsset.headers.'Content-Type'
$staticAssetReady = $result.staticAsset.statusCode -eq 200 -and
  $staticCacheControl -match 'immutable' -and
  -not [string]::IsNullOrWhiteSpace($staticContentType) -and
  $staticContentType -notmatch '^text/html'

$ready = $result.rootA -contains $EcsIp -and
  $wwwCnameReady -and
  $staticCnameReady -and
  $result.rootHttpsHealth.statusCode -eq 200 -and
  $healthUp -and
  $rootRedirectReady -and
  $wwwRedirectReady -and
  $result.rootTls.daysRemaining -ge 7 -and
  $result.wwwTls.daysRemaining -ge 7 -and
  $result.staticTls.daysRemaining -ge 7 -and
  $staticAssetReady -and
  $result.publicOssAnonymous.statusCode -eq 403 -and
  -not [string]::IsNullOrWhiteSpace($result.ecs.ramRole) -and
  $result.ecs.ramRole -ne 'none' -and
  $result.ecs.storageType -eq 'oss' -and
  $result.ecs.privateBucketSet -and
  $result.ecs.publicBucketSet -and
  $result.ecs.publicEndpointSet -and
  $result.ecs.cdnBase -eq "https://$StaticDomain" -and
  $result.ecs.nginxHttpsActive -and
  $result.ecs.appHealth

$result.ready = $ready
$result | ConvertTo-Json -Depth 5
if (-not $ready) { exit 2 }
