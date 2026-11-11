# Secret (NON vanno su GitHub)

In questa cartella servono due file, ignorati da git:

1. `keys.env`: copia `keys.env.example` e metti chiavi lunghe e casuali.
   Per generarne una in PowerShell:

   ```powershell
   -join ((48..57)+(97..122) | Get-Random -Count 32 | ForEach-Object {[char]$_})
   ```

2. `advent.json`: i contenuti veri delle 24 caselle
   (stesso formato di `advent.example.json` nella cartella principale).
