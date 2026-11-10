<#
.SYNOPSIS
  token 檢查：介面只能用設計 token，不得寫死色碼與尺寸。

.DESCRIPTION
  有違規時逐行列出「檔案:行號: 規則 說明」並以結束碼 1 失敗；沒有違規時結束碼 0。
  例外：該行行尾加註解「token-check: allow <理由>」（只限展示頁色票之類的必要情況，里程碑檢視時逐一檢查）。

  完成的定義第 3 點與 CI 執行：pwsh -NoProfile -File scripts/check-tokens.ps1
  自我測試（確認每條規則都會擋、合法寫法不會誤擋）：pwsh -NoProfile -File scripts/check-tokens.ps1 -SelfTest
  自我測試的檔案在 scripts/check-tokens-fixtures/，路徑結構和專案相同；每個檔案第一行寫
  「token-check-expect: <規則>」（或 none＝不得有任何違規）。

.PARAMETER Root
  要檢查的根目錄（預設是專案根目錄）。

.PARAMETER SelfTest
  改為檢查 scripts/check-tokens-fixtures/ 並比對每個檔案預期的規則。
#>
param(
    [string]$Root = (Join-Path $PSScriptRoot '..'),
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# 中文訊息在 Windows 主控台、Git Bash 與 CI 的日誌都不亂碼
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# px 白名單（後三者是清單列高與欄寬）
$AllowedPx = @(0, 1, 2, 4, 8, 12, 14, 16, 20, 24, 32, 48, 220, 360)
# 這些屬性的數字不是尺寸
$UnitlessProps = '^-fx-(opacity|scale-[xyz]|rotate|indeterminate-segment-count|font-weight|.*-count)$'

# CSS 色名（transparent 允許，不列入）
$CssColorNames = @(
    'aliceblue', 'antiquewhite', 'aqua', 'aquamarine', 'azure', 'beige', 'bisque', 'black', 'blanchedalmond', 'blue',
    'blueviolet', 'brown', 'burlywood', 'cadetblue', 'chartreuse', 'chocolate', 'coral', 'cornflowerblue', 'cornsilk',
    'crimson', 'cyan', 'darkblue', 'darkcyan', 'darkgoldenrod', 'darkgray', 'darkgreen', 'darkgrey', 'darkkhaki',
    'darkmagenta', 'darkolivegreen', 'darkorange', 'darkorchid', 'darkred', 'darksalmon', 'darkseagreen',
    'darkslateblue', 'darkslategray', 'darkslategrey', 'darkturquoise', 'darkviolet', 'deeppink', 'deepskyblue',
    'dimgray', 'dimgrey', 'dodgerblue', 'firebrick', 'floralwhite', 'forestgreen', 'fuchsia', 'gainsboro',
    'ghostwhite', 'gold', 'goldenrod', 'gray', 'green', 'greenyellow', 'grey', 'honeydew', 'hotpink', 'indianred',
    'indigo', 'ivory', 'khaki', 'lavender', 'lavenderblush', 'lawngreen', 'lemonchiffon', 'lightblue', 'lightcoral',
    'lightcyan', 'lightgoldenrodyellow', 'lightgray', 'lightgreen', 'lightgrey', 'lightpink', 'lightsalmon',
    'lightseagreen', 'lightskyblue', 'lightslategray', 'lightslategrey', 'lightsteelblue', 'lightyellow', 'lime',
    'limegreen', 'linen', 'magenta', 'maroon', 'mediumaquamarine', 'mediumblue', 'mediumorchid', 'mediumpurple',
    'mediumseagreen', 'mediumslateblue', 'mediumspringgreen', 'mediumturquoise', 'mediumvioletred', 'midnightblue',
    'mintcream', 'mistyrose', 'moccasin', 'navajowhite', 'navy', 'oldlace', 'olive', 'olivedrab', 'orange',
    'orangered', 'orchid', 'palegoldenrod', 'palegreen', 'paleturquoise', 'palevioletred', 'papayawhip', 'peachpuff',
    'peru', 'pink', 'plum', 'powderblue', 'purple', 'red', 'rosybrown', 'royalblue', 'saddlebrown', 'salmon',
    'sandybrown', 'seagreen', 'seashell', 'sienna', 'silver', 'skyblue', 'slateblue', 'slategray', 'slategrey', 'snow',
    'springgreen', 'steelblue', 'tan', 'teal', 'thistle', 'tomato', 'turquoise', 'violet', 'wheat', 'white',
    'whitesmoke', 'yellow', 'yellowgreen'
)
$ColorNamePattern = '(?i)(?<![\w-])(' + ($CssColorNames -join '|') + ')(?![\w-])'
$HexPattern = '#[0-9a-fA-F]{3,8}(?![0-9a-zA-Z])'
$AllowPattern = 'token-check:\s*allow\s+\S'

# 規則 → 說明（自我測試確認每條規則都有測試檔）
$Rules = [ordered]@{
    'css-color'        = '樣式表只能引用 -sv-* 顏色（色碼、rgb()、derive()、ladder()、CSS 色名只能寫在 theme-*.css）'
    'css-px'           = '尺寸只能用 px 白名單'
    'java-style'       = 'desktop 程式不得呼叫 setStyle( / Font.font( / new Font(，樣式寫在樣式表'
    'java-color'       = 'desktop 程式不得建立顏色（Color.web/rgb/hsb/color、Color.常數、色碼字串）'
    'android-color'    = 'android 版面與 drawable 只能引用 @color/sv_* 或主題屬性'
    'android-dimen'    = 'android 版面不得寫死 dp/sp（0dp 除外），尺寸放 sv_dimens.xml'
    'android-textsize' = 'android 版面不得用 android:textSize，改用 textAppearance'
    'android-text'     = 'android 版面不得寫死 android:text，文字放 strings.xml'
    'android-values'   = 'android 只有 sv_colors.xml、sv_dimens.xml 可以定義顏色與尺寸'
    'android-java'     = 'android 程式不得建立顏色或設定字級（Color.parseColor/rgb/argb、0xFF 色碼、setTextSize）'
}

function New-Violation([string]$file, [int]$line, [string]$rule) {
    [pscustomobject]@{ File = $file; Line = $line; Rule = $rule }
}

function Get-RelativePath([string]$base, [string]$path) {
    [System.IO.Path]::GetRelativePath($base, $path).Replace('\', '/')
}

function Test-Css([string]$rel, [string[]]$lines) {
    $out = [System.Collections.Generic.List[object]]::new()
    $inComment = $false
    $depth = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $raw = $lines[$i]
        if ($raw -match $AllowPattern) { continue }
        # 去掉註解（可能跨行）
        $sb = [System.Text.StringBuilder]::new()
        $j = 0
        while ($j -lt $raw.Length) {
            if ($inComment) {
                $end = $raw.IndexOf('*/', $j)
                if ($end -lt 0) { $j = $raw.Length } else { $inComment = $false; $j = $end + 2 }
            } else {
                $start = $raw.IndexOf('/*', $j)
                if ($start -lt 0) { [void]$sb.Append($raw.Substring($j)); $j = $raw.Length }
                else { [void]$sb.Append($raw.Substring($j, $start - $j)); $inComment = $true; $j = $start + 2 }
            }
        }
        # 去掉字串（-fx-shape 的路徑、字型名稱）
        $code = [regex]::Replace($sb.ToString(), '"[^"]*"|''[^'']*''', '""')
        # 只看大括號裡的宣告；選擇器（例：.a:hover）不檢查
        $decl = [System.Text.StringBuilder]::new()
        foreach ($ch in $code.ToCharArray()) {
            if ($ch -eq '{') { $depth++; [void]$decl.Append(';'); continue }
            if ($ch -eq '}') { $depth = [Math]::Max(0, $depth - 1); [void]$decl.Append(';'); continue }
            if ($depth -gt 0) { [void]$decl.Append($ch) }
        }
        $colorHit = $false
        $pxHit = $false
        foreach ($m in [regex]::Matches($decl.ToString(), '(-?[A-Za-z][\w-]*)\s*:\s*([^;]*)')) {
            $prop = $m.Groups[1].Value
            $value = $m.Groups[2].Value
            # 查找色（-sv-fg 等）的名稱不算色名
            $bare = [regex]::Replace($value, '(?<![\w])-[A-Za-z][\w-]*', ' ')
            if ($value -match $HexPattern -or $value -match '(?i)\b(rgb|rgba|hsb|hsba|derive|ladder)\s*\(' -or
                $bare -match $ColorNamePattern) {
                $colorHit = $true
            }
            if ($prop -notmatch $UnitlessProps) {
                # 顏色函式的參數不是尺寸（它們已經算 css-color 違規）
                $sizes = [regex]::Replace($bare, '(?i)\b(rgb|rgba|hsb|hsba|derive|ladder)\s*\([^)]*\)', ' ')
                foreach ($n in [regex]::Matches($sizes,'(?<![\w#.])-?(\d*\.?\d+)(px|em|%)?(?![\w%.])')) {
                    $num = [double]$n.Groups[1].Value
                    $unit = $n.Groups[2].Value
                    if ($unit -eq 'em' -or $unit -eq '%' -or $AllowedPx -notcontains $num) { $pxHit = $true }
                }
            }
        }
        if ($colorHit) { $out.Add((New-Violation $rel ($i + 1) 'css-color')) }
        if ($pxHit) { $out.Add((New-Violation $rel ($i + 1) 'css-px')) }
    }
    $out
}

function Test-Lines([string]$rel, [string[]]$lines, [object[]]$checks) {
    $out = [System.Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match $AllowPattern) { continue }
        foreach ($c in $checks) {
            if ($line -cmatch $c.Pattern) {
                $out.Add((New-Violation $rel ($i + 1) $c.Rule))
            }
        }
    }
    $out
}

$JavaDesktopChecks = @(
    @{ Rule = 'java-style'; Pattern = '\.setStyle\s*\(|\bFont\.font\s*\(|\bnew\s+Font\s*\(' }
    @{ Rule = 'java-color'; Pattern = '\bColor\.(web|rgb|hsb|color|grayRgb|gray)\s*\(|\bColor\.[A-Z][A-Z_]+\b|"#[0-9a-fA-F]{3,8}"' }
)
$AndroidResChecks = @(
    @{ Rule = 'android-color'; Pattern = '="#[0-9a-fA-F]{3,8}"|@android:color/' }
    # 0dp 在檢查前先移除（ConstraintLayout 的「依約束決定」寫法）
    @{ Rule = 'android-dimen'; Pattern = '="-?\d+(\.\d+)?(dp|dip|sp|px)"' }
    @{ Rule = 'android-textsize'; Pattern = 'android:textSize\s*=' }
    @{ Rule = 'android-text'; Pattern = 'android:text\s*=\s*"(?![@?])[^"]+"' }
)
$AndroidValuesChecks = @(
    @{ Rule = 'android-values'; Pattern = '<color\b|<dimen\b|>#[0-9a-fA-F]{3,8}<|"#[0-9a-fA-F]{3,8}"' }
)
$AndroidJavaChecks = @(
    @{ Rule = 'android-java'; Pattern = '\bColor\.(parseColor|rgb|argb)\s*\(|\b0[xX][fF]{2}[0-9a-fA-F]{6}\b|\.setTextSize\s*\(' }
)

function Get-Violations([string]$base) {
    $base = (Resolve-Path $base).Path
    $all = [System.Collections.Generic.List[object]]::new()
    $files = Get-ChildItem -Path $base -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/](build|\.gradle|\.git|\.localdata|\.worktrees|node_modules)[\\/]' }
    foreach ($f in $files) {
        $rel = Get-RelativePath $base $f.FullName
        $lines = [string[]](Get-Content -LiteralPath $f.FullName -Encoding UTF8)
        if ($null -eq $lines) { continue }
        if ($rel -match '^desktop/src/[^/]+/resources/.+\.css$') {
            if ($f.Name -notmatch '^theme-.+\.css$') { foreach ($v in @(Test-Css $rel $lines)) { $all.Add($v) } }
        } elseif ($rel -match '^desktop/src/main/java/.+\.java$') {
            foreach ($v in @(Test-Lines $rel $lines $JavaDesktopChecks)) { $all.Add($v) }
        } elseif ($rel -match '^android/src/[^/]+/res/(layout[^/]*|drawable[^/]*|menu)/.+\.xml$') {
            $isVector = ($lines -join "`n") -match '<vector\b'
            $checked = foreach ($l in $lines) {
                $l = [regex]::Replace($l, '="0dp"', '=""')
                # vector drawable 的 android:width／height 是圖檔本身的固有尺寸（格式必填，例：自適應圖示固定 108dp），
                # 不是版面尺寸，不檢查；版面與 shape 的尺寸照常檢查
                if ($isVector) { $l = [regex]::Replace($l, 'android:(width|height)="[^"]*"', '') }
                $l
            }
            foreach ($v in @(Test-Lines $rel ([string[]]$checked) $AndroidResChecks)) { $all.Add($v) }
        } elseif ($rel -match '^android/src/[^/]+/res/values[^/]*/[^/]+\.xml$') {
            if ($f.Name -notin @('sv_colors.xml', 'sv_dimens.xml')) {
                foreach ($v in @(Test-Lines $rel $lines $AndroidValuesChecks)) { $all.Add($v) }
            }
        } elseif ($rel -match '^android/src/[^/]+/java/.+\.java$') {
            foreach ($v in @(Test-Lines $rel $lines $AndroidJavaChecks)) { $all.Add($v) }
        }
    }
    $all
}

if ($SelfTest) {
    $fixtures = Join-Path $PSScriptRoot 'check-tokens-fixtures'
    $found = @(Get-Violations $fixtures)
    $failures = [System.Collections.Generic.List[string]]::new()
    $covered = @{}
    foreach ($f in Get-ChildItem -Path $fixtures -Recurse -File) {
        $rel = Get-RelativePath (Resolve-Path $fixtures).Path $f.FullName
        $first = (Get-Content -LiteralPath $f.FullName -TotalCount 1 -Encoding UTF8)
        if ($first -notmatch 'token-check-expect:\s*([\w-]+)') {
            $failures.Add("$rel：第一行沒有 token-check-expect")
            continue
        }
        $expect = $Matches[1]
        $mine = @($found | Where-Object { $_.File -eq $rel })
        if ($expect -eq 'none') {
            foreach ($v in $mine) { $failures.Add("$rel`:$($v.Line)：合法寫法被誤擋（$($v.Rule)）") }
        } else {
            if (-not $Rules.Contains($expect)) { $failures.Add("$rel：未知的規則 $expect"); continue }
            $covered[$expect] = $true
            if (-not ($mine | Where-Object { $_.Rule -eq $expect })) { $failures.Add("$rel：沒有擋下（預期 $expect）") }
            foreach ($v in $mine | Where-Object { $_.Rule -ne $expect }) {
                $failures.Add("$rel`:$($v.Line)：多出規則 $($v.Rule)（預期只有 $expect）")
            }
        }
    }
    foreach ($r in $Rules.Keys) {
        if (-not $covered.ContainsKey($r)) { $failures.Add("規則 $r 沒有測試檔") }
    }
    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Output "自我測試失敗：$_" }
        exit 1
    }
    Write-Output "token 檢查自我測試通過：$($Rules.Count) 條規則都會擋，合法寫法沒有誤擋（$($found.Count) 筆預期違規）"
    exit 0
}

$violations = @(Get-Violations $Root)
if ($violations.Count -gt 0) {
    foreach ($v in $violations | Sort-Object File, Line) {
        Write-Output "$($v.File):$($v.Line): $($v.Rule) $($Rules[$v.Rule])"
    }
    Write-Output "token 檢查失敗：$($violations.Count) 處違規"
    exit 1
}
Write-Output 'token 檢查通過：沒有違規'
exit 0
