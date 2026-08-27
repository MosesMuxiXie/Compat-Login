param(
    [Parameter(Mandatory = $true)]
    [string] $MinecraftVersion,
    [string] $LoaderVersion = "0.19.3",
    [string] $InstallerVersion = "1.1.2",
    [Parameter(Mandatory = $true)]
    [string] $ModJar,
    # Minecraft 26.x needs Java 25+, while the oldest supported releases need an older JVM.
    [string] $JavaExecutable = "java",
    # The window also covers downloading the vanilla server JAR and generating the first world.
    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$resolvedJar = (Resolve-Path -LiteralPath $ModJar).Path
$resolvedJava = (Get-Command $JavaExecutable).Source

# The server keeps its log files open, so they must be read with a shared handle.
function Read-SharedText([string] $path) {
    if (!(Test-Path -LiteralPath $path)) {
        return ""
    }
    $stream = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $reader = New-Object IO.StreamReader($stream)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$workDir = Join-Path $tempBase ("compat-login-smoke-" + [guid]::NewGuid().ToString("N"))
$workDir = [IO.Path]::GetFullPath($workDir)
if (!$workDir.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use unsafe smoke-test directory: $workDir"
}

$serverProcess = $null
try {
    $modsDir = Join-Path $workDir "mods"
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
    Copy-Item -LiteralPath $resolvedJar -Destination $modsDir

    $launcher = Join-Path $workDir "fabric-server.jar"
    $launcherUrl = "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion/$LoaderVersion/$InstallerVersion/server/jar"
    Invoke-WebRequest -UseBasicParsing -Uri $launcherUrl -OutFile $launcher

    [IO.File]::WriteAllText((Join-Path $workDir "eula.txt"), "eula=true`n")
    [IO.File]::WriteAllText(
        (Join-Path $workDir "server.properties"),
        "online-mode=true`nenforce-secure-profile=false`nserver-port=0`nview-distance=2`nsimulation-distance=2`nsync-chunk-writes=false`n"
    )

    $stdout = Join-Path $workDir "stdout.log"
    $stderr = Join-Path $workDir "stderr.log"
    $serverProcess = Start-Process `
        -FilePath $resolvedJava `
        -ArgumentList @("-Xms256M", "-Xmx1G", "-jar", "fabric-server.jar", "nogui") `
        -WorkingDirectory $workDir `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    $latestLog = Join-Path $workDir "logs\latest.log"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $started = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 1000
        $serverProcess.Refresh()
        $logText = Read-SharedText $latestLog
        if ($logText.Contains("Done (")) {
            $started = $true
            break
        }
        if ($serverProcess.HasExited) {
            break
        }
    }

    if (!$serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id -Force
        $serverProcess.WaitForExit()
    }

    $combinedLog = ""
    foreach ($logFile in @($latestLog, $stdout, $stderr)) {
        $combinedLog += (Read-SharedText $logFile) + "`n"
    }
    $badPattern = "Mixin apply failed|InjectionError|InvalidInjectionException|ExceptionInInitializerError"
    $hasMixinFailure = $combinedLog -match $badPattern

    $combinedLog -split "`r?`n" |
        Where-Object { $_ -match "Loading Minecraft|Compat Login initialized|Done \(|$badPattern" } |
        Select-Object -Unique |
        ForEach-Object { Write-Output $_ }

    if (!$started) {
        throw "Minecraft $MinecraftVersion did not reach the Done state within $TimeoutSeconds seconds"
    }
    if ($hasMixinFailure) {
        throw "Minecraft $MinecraftVersion reported a Mixin or initialization failure"
    }
    if ($combinedLog -notmatch "Compat Login initialized with") {
        throw "Compat Login did not initialize on Minecraft $MinecraftVersion"
    }
    Write-Output "Smoke test passed for Minecraft $MinecraftVersion with $([IO.Path]::GetFileName($resolvedJar))"
} finally {
    if ($null -ne $serverProcess) {
        $serverProcess.Refresh()
        if (!$serverProcess.HasExited) {
            Stop-Process -Id $serverProcess.Id -Force
            $serverProcess.WaitForExit()
        }
        $serverProcess.Dispose()
    }
    if (Test-Path -LiteralPath $workDir) {
        $verifiedWorkDir = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $workDir).Path)
        if ($verifiedWorkDir.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($verifiedWorkDir).StartsWith("compat-login-smoke-", [StringComparison]::Ordinal)) {
            for ($attempt = 0; $attempt -lt 10 -and (Test-Path -LiteralPath $verifiedWorkDir); $attempt++) {
                Start-Sleep -Milliseconds 500
                Remove-Item -LiteralPath $verifiedWorkDir -Recurse -Force -ErrorAction SilentlyContinue
            }
            if (Test-Path -LiteralPath $verifiedWorkDir) {
                Write-Warning "Could not remove smoke-test directory after waiting for file locks: $verifiedWorkDir"
            }
        } else {
            Write-Warning "Smoke-test directory was not removed because its path failed validation: $verifiedWorkDir"
        }
    }
}
