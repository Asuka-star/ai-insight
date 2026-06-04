# Project Editing Notes

## Encoding Safety

- Source files are UTF-8 without BOM. Keep `.java`, `.ts`, `.tsx`, `.css`, `.xml`, `.md`, and resource text files in UTF-8.
- On Windows/PowerShell, do not rewrite source files with plain `Set-Content`, `Out-File`, or redirected output unless the command explicitly writes UTF-8 without BOM.
- Prefer `apply_patch` for manual edits. If a script must rewrite a file, use an explicit encoding API, for example:

```powershell
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
```

- Do not use ad hoc transcoding between GBK/ANSI and UTF-8 for source files.
- Run `mvn -Dtest=SourceEncodingGuardTest test` after touching files with Chinese text. The guard rejects non-UTF-8 files, UTF-8 BOM, and newly introduced mojibake markers.
