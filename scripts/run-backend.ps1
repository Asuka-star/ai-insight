$ErrorActionPreference = "Stop"

chcp 65001 | Out-Null
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:LOGGING_CHARSET_CONSOLE = "UTF-8"
$env:LOGGING_CHARSET_FILE = "UTF-8"
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 $env:JAVA_TOOL_OPTIONS".Trim()

mvn spring-boot:run
