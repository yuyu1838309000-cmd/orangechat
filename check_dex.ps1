Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = 'D:\a12\orangechat\app\build\outputs\apk\debug\app-universal-debug.apk'
$z = [System.IO.Compression.ZipFile]::OpenRead($apk)
foreach ($entry in $z.Entries) {
    if ($entry.FullName -like 'classes*.dex') {
        $ms = New-Object System.IO.MemoryStream
        $entry.Open().CopyTo($ms)
        $bytes = $ms.ToArray()
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        $hitOld = $text -match 'app/app/getAppAccessToken'
        $hitNew = $false
        # match the corrected full URL only (not the doc comment's same string... both are app/ though)
        $hitNew = $text -match 'qq\.com/app/getAppAccessToken'
        Write-Output ("{0}  old(app/app)={1}  new(./app/getAppAccessToken)={2}" -f $entry.FullName, $hitOld, $hitNew)
    }
}
$z.Dispose()
