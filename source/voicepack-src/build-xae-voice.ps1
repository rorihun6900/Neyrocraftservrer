param(
    [Parameter(Mandatory = $true)][string]$Greeting,
    [string]$Blender = 'C:/Games/Steam/steamapps/common/Blender/blender.exe',
    [string]$Output = (Join-Path $PSScriptRoot '../neyrohub-src/resources/voice/xae'),
    [string]$Dialogue = (Join-Path $PSScriptRoot 'xae-dialogue.tsv')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$greetingPath = (Resolve-Path -LiteralPath $Greeting).Path
$dialoguePath = (Resolve-Path -LiteralPath $Dialogue).Path
$outputPath = [IO.Path]::GetFullPath($Output)
$work = New-Item -Type Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('neyro-xae-audio-' + [guid]::NewGuid()))
New-Item -Type Directory -Path $outputPath -Force | Out-Null
$synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
try {
    $synth.SelectVoice('Microsoft Irina Desktop')
    $format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(
        48000,
        [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
        [System.Speech.AudioFormat.AudioChannel]::Mono
    )
    foreach ($line in (Get-Content -LiteralPath $dialoguePath -Encoding UTF8)) {
        $id, $subtitle = $line.Split([char]9, 2)
        if ($id -notmatch '^[a-z][a-z0-9_]{0,31}$' -or [string]::IsNullOrWhiteSpace($subtitle)) {
            throw "Invalid dialogue row for key '$id'."
        }
        $spoken = $subtitle.Replace([char]0x2019, ' ').Replace('Нейро-Сити', 'Нейро Сити')
        $speech = [System.Security.SecurityElement]::Escape($spoken)
        $speech = $speech.Replace('…', '<break time="260ms"/>')
        $ssml = @"
<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ru-RU">
  <prosody pitch="+7%" rate="-8%" volume="88">$speech</prosody>
</speak>
"@
        $synth.SetOutputToWaveFile((Join-Path $work.FullName "$id.wav"), $format)
        $synth.SpeakSsml($ssml)
        $synth.SetOutputToNull()
    }
    & $Blender -b --factory-startup --python-exit-code 1 --python (Join-Path $PSScriptRoot 'xae-voice.py') -- $greetingPath $dialoguePath $work.FullName $outputPath
    if ($LASTEXITCODE -ne 0) { throw "Blender audio conversion failed ($LASTEXITCODE)." }
} finally {
    $synth.Dispose()
    # Delete only this script's generated WAV files in the explicitly created temp directory.
    Get-ChildItem -LiteralPath $work.FullName -Filter '*.wav' -File | ForEach-Object {
        Remove-Item -LiteralPath $_.FullName
    }
    Remove-Item -LiteralPath $work.FullName
}
