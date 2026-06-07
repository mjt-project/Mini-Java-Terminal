$File = "Test.java"
$MainClass = "Test"
$JarName = "server.jar"
$ManifestFile = "manifest.txt"
$SourceId = "AutoBuildJava"

Get-EventSubscriber -SourceIdentifier $SourceId -ErrorAction SilentlyContinue | Unregister-Event
Get-Event -SourceIdentifier $SourceId -ErrorAction SilentlyContinue | Remove-Event

$Watcher = New-Object System.IO.FileSystemWatcher
$Watcher.Path = $PSScriptRoot
$Watcher.Filter = $File
$Watcher.NotifyFilter = [System.IO.NotifyFilters]::LastWrite
$Watcher.EnableRaisingEvents = $true

Register-ObjectEvent -InputObject $Watcher -EventName Changed -SourceIdentifier $SourceId | Out-Null

Write-Host ">>> Monitoring file $File... Ctrl + S builds JAR automatically! <<<" -ForegroundColor Cyan
Write-Host ">>> Press Ctrl + C to stop. <<<" -ForegroundColor DarkGray

function Build-Jar {
    Write-Host "`n[+] Change detected! Rebuilding..." -ForegroundColor Yellow

    Start-Sleep -Milliseconds 700

    if (Test-Path "$MainClass.class") {
        Remove-Item "$MainClass.class" -Force
    }

    javac $File

    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Java code has errors, cannot build!" -ForegroundColor Red
        return
    }

    Set-Content -Path $ManifestFile -Value "Main-Class: $MainClass`n"

    jar cvfm $JarName $ManifestFile "$MainClass.class" > $null

    if ($LASTEXITCODE -eq 0) {
        Write-Host "[SUCCESS] Updated file $JarName successfully!" -ForegroundColor Green
    } else {
        Write-Host "[ERROR] Error creating JAR file!" -ForegroundColor Red
    }
}

try {
    while ($true) {
        $event = Wait-Event -SourceIdentifier $SourceId

        Remove-Event -EventIdentifier $event.EventIdentifier

        Start-Sleep -Milliseconds 1000

        Get-Event -SourceIdentifier $SourceId -ErrorAction SilentlyContinue | Remove-Event

        Build-Jar
    }
}
finally {
    Get-EventSubscriber -SourceIdentifier $SourceId -ErrorAction SilentlyContinue | Unregister-Event
    Get-Event -SourceIdentifier $SourceId -ErrorAction SilentlyContinue | Remove-Event

    $Watcher.EnableRaisingEvents = $false
    $Watcher.Dispose()

    Write-Host "`n>>> Auto build stopped. <<<" -ForegroundColor DarkGray
}