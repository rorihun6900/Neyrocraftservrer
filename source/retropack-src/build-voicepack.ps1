# Builds the main NeyroCraft resource pack (voices, music, Ami's hologram, energy swords).
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent          # ...\NeyroCraft\source
$vs = "$root\voicepack-src"
$res = "$root\neyrohub-src\resources"
$saber = "$root\retropack-src\saberpack"
$zip = "$res\voice\neyrocraft-voice.zip"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
if (Test-Path $zip) { Remove-Item $zip }

$obj = [ordered]@{}
foreach ($n in 'sam_welcome_a', 'sam_welcome_b', 'sam_restart', 'sam_shutdown', 'hub_ambient', 'cinema_trailer', 'city_music', 'radio_news') {
    $sub = if ($n -eq 'hub_ambient') { 'Музыка корабля' } elseif ($n -eq 'cinema_trailer') { 'Кинотеатр: трейлер NeyroCraft' } elseif ($n -eq 'city_music') { 'Музыка Neyro-City' } elseif ($n -eq 'radio_news') { 'Нейро-Радио' } else { 'Саманта говорит' }
    $obj[$n] = [ordered]@{ sounds = @(@{ name = "neyrocraft:$n"; stream = $true }); subtitle = $sub }
}
Get-ChildItem "$vs\ami_*.ogg" | ForEach-Object { $obj[$_.BaseName] = [ordered]@{ sounds = @(@{ name = "neyrocraft:$($_.BaseName)"; stream = $true }); subtitle = 'Ами говорит' } }
$saberEvents = [ordered]@{
    'saber.hum' = @('saber_hum'); 'saber.on' = @('saber_on'); 'saber.off' = @('saber_off')
    'saber.swing' = @('saber_swing1', 'saber_swing2', 'saber_swing3'); 'saber.clash' = @('saber_clash1', 'saber_clash2')
    'saber.hit' = @('saber_hit'); 'saber.zap' = @('saber_zap'); 'saber.push' = @('saber_push')
    'saber.dash' = @('saber_dash'); 'saber.jump' = @('saber_jump')
    'speeder.engine' = @('speeder_engine'); 'speeder.boost' = @('speeder_boost')
    'laser.shot' = @('blaster_shot1', 'blaster_shot2'); 'laser.hit' = @('blaster_hit'); 'laser.overheat' = @('blaster_overheat')
    'starfight.engine' = @('fighter_engine'); 'starfight.laser' = @('fighter_laser1', 'fighter_laser2'); 'starfight.hit' = @('fighter_hit')
    'starfight.explode' = @('fighter_explode'); 'starfight.boost' = @('fighter_boost'); 'starfight.alarm' = @('fighter_alarm')
}
foreach ($k in $saberEvents.Keys) {
    $dirName = if ($k -like 'speeder.*') { 'speeder' } elseif ($k -like 'laser.*') { 'laser' } elseif ($k -like 'starfight.*') { 'starfight' } else { 'saber' }; $sub = if ($k -like 'speeder.*') { 'Спидер' } elseif ($k -like 'laser.*') { 'Бластер' } elseif ($k -like 'starfight.*') { 'Истребитель' } else { 'Световой меч' }
    $obj[$k] = [ordered]@{ sounds = @($saberEvents[$k] | ForEach-Object { "neyrocraft:$dirName/$_" }); subtitle = $sub }
}
$sounds = $obj | ConvertTo-Json -Depth 6 -Compress
$mcmeta = '{"pack":{"description":"NeyroCraft: голоса, музыка, Ами и световые мечи","pack_format":75,"min_format":[69,0],"max_format":200}}'
$font = '{"providers":[{"type":"bitmap","file":"neyrocraft:font/ami_holo.png","ascent":64,"height":64,"chars":["\uE000"]}]}'

$fs = [IO.File]::Open($zip, 'Create')
$za = New-Object IO.Compression.ZipArchive($fs, [IO.Compression.ZipArchiveMode]::Create)
$utf = New-Object Text.UTF8Encoding($false)
function AddText($entry, $text) { $e = $za.CreateEntry($entry); $w = New-Object IO.StreamWriter($e.Open(), $utf); $w.Write($text); $w.Close() }
function AddFile($entry, $path) { $b = [IO.File]::ReadAllBytes($path); $e = $za.CreateEntry($entry); $s = $e.Open(); $s.Write($b, 0, $b.Length); $s.Close() }
AddText 'pack.mcmeta' $mcmeta
AddText 'assets/neyrocraft/sounds.json' $sounds
AddText 'assets/neyrocraft/font/holo.json' $font
AddText 'assets/neyrocraft/font/screen.json' '{"providers":[{"type":"bitmap","file":"neyrocraft:font/pixel.png","ascent":7,"height":8,"chars":["\uE200"]}]}'
AddFile 'assets/neyrocraft/textures/font/pixel.png' "$res\cinema\pixel.png"
# character screen background: a 176x222 glyph drawn over the 6-row chest window (see NeyroKarma.java)
AddText 'assets/neyrocraft/font/gui.json' '{"providers":[{"type":"bitmap","file":"neyrocraft:font/gui_character.png","ascent":13,"height":222,"chars":[""]},{"type":"space","advances":{"":-8,"":-167}}]}'
AddFile 'assets/neyrocraft/textures/font/gui_character.png' "$vs\gui_character.png"
# the player's own inventory (E) in the old sci-fi RPG style, the clone guards' skin, the light/dark side
# shown as the Luck / Bad Luck effect with its own icons and names (drawn by voicepack-src\gui_inventory.py)
AddFile 'assets/minecraft/textures/gui/container/inventory.png' "$vs\inventory.png"
AddFile 'assets/neyrocraft/textures/entity/clone_trooper.png' "$vs\clone_trooper.png"
AddFile 'assets/minecraft/textures/mob_effect/luck.png' "$vs\side_light.png"
AddFile 'assets/minecraft/textures/mob_effect/unluck.png' "$vs\side_dark.png"
# the HUD in the same style: hotbar, selection, offhand, experience bar, effect backgrounds
foreach ($n in 'hotbar', 'hotbar_selection', 'hotbar_offhand_left', 'hotbar_offhand_right', 'experience_bar_background',
               'experience_bar_progress', 'locator_bar_background', 'effect_background', 'effect_background_ambient') {
    AddFile "assets/minecraft/textures/gui/sprites/hud/$n.png" "$vs\hud\$n.png"
}
foreach ($n in 'effect_background_large', 'effect_background_small') {
    AddFile "assets/minecraft/textures/gui/sprites/container/inventory/$n.png" "$vs\hud\$n.png"
}
$sideNames = '{"effect.minecraft.luck":"Светлая сторона","effect.minecraft.unluck":"Тёмная сторона"}'
AddText 'assets/minecraft/lang/ru_ru.json' $sideNames
AddText 'assets/minecraft/lang/en_us.json' $sideNames
foreach ($n in 'sam_welcome_a', 'sam_welcome_b', 'sam_restart', 'sam_shutdown', 'hub_ambient', 'cinema_trailer', 'city_music', 'radio_news') { AddFile "assets/neyrocraft/sounds/$n.ogg" "$vs\$n.ogg" }
Get-ChildItem "$vs\ami_*.ogg" | ForEach-Object { AddFile "assets/neyrocraft/sounds/$($_.Name)" $_.FullName }
AddFile 'assets/neyrocraft/textures/font/ami_holo.png' "$res\ami\ami_holo.png"
Get-ChildItem $saber -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($saber.Length + 1) -replace '\\', '/'
    AddFile $rel $_.FullName
}
$za.Dispose(); $fs.Close()
"pack: $([math]::Round((Get-Item $zip).Length / 1KB)) KB, entries: $(([IO.Compression.ZipFile]::OpenRead($zip)).Entries.Count)"
