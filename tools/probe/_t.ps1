$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Storage.Streams.IOutputStream, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.Streams.IInputStream,  Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
[Windows.Networking.Sockets.StreamSocket, Windows.Networking.Sockets, ContentType = WindowsRuntime] | Out-Null

$sock = New-Object Windows.Networking.Sockets.StreamSocket
$rawOut = $sock.OutputStream
Write-Output ("rawOut type: " + $rawOut.GetType().FullName)
$castOut = [Windows.Storage.Streams.IOutputStream]$rawOut
Write-Output ("castOut type: " + $castOut.GetType().FullName)
$m = $castOut.GetType().GetMethods() | Where-Object { $_.Name -eq "WriteAsync" } | Select-Object -First 1
Write-Output ("has WriteAsync: " + ($null -ne $m))
$sock.Dispose()
