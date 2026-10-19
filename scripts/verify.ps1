# Build from the repository location without changing the caller's directory.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$projectWrapper = Join-Path $projectRoot 'gradlew.bat'
$projectReports = Join-Path $projectRoot 'build-reports'
$projectTemp = [IO.Path]::GetFullPath((Join-Path $projectRoot '.tmp'))
New-Item -ItemType Directory -Path $projectReports -Force | Out-Null
$projectFailed = $false
try {
    $projectChecks = @(
        @{ Task = ':app:assembleDebug'; Log = 'assembleDebug.log' },
        @{ Task = 'test'; Log = 'test.log' }
    )
    if (Test-Path -LiteralPath (Join-Path $projectRoot 'nativebridge/src/main/cpp/CMakeLists.txt')) {
        $projectChecks += @{ Task = ':nativebridge:externalNativeBuildDebug'; Log = 'native-build.log' }
    }
    foreach ($projectCheck in $projectChecks) {
        & $projectWrapper $projectCheck.Task --no-daemon --console=plain --stacktrace 2>&1 |
            Tee-Object -FilePath (Join-Path $projectReports $projectCheck.Log)
        $projectExitCode = $LASTEXITCODE
        Write-Output "$($projectCheck.Task): exit $projectExitCode"
        if ($projectExitCode -ne 0) { $projectFailed = $true }
    }
} finally {
    & $projectWrapper --stop --console=plain 2>&1 | Out-Null
    # Guard the exact target before recursively deleting only the build temp directory.
    $projectExpectedTemp = [IO.Path]::Combine($projectRoot, '.tmp')
    if ($projectTemp -ne $projectExpectedTemp) { throw 'Unexpected cleanup target' }
    if (Test-Path -LiteralPath $projectTemp) {
        if ((Get-Item -LiteralPath $projectTemp).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Refusing to clean a redirected temporary directory'
        }
        Remove-Item -LiteralPath $projectTemp -Recurse -Force
    }
}
if ($projectFailed) { exit 1 }
