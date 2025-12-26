# Telegram MCP connector

Servidor MCP (STDIO) minimalista para que Cursor envíe archivos locales a un chat de Telegram usando `sendDocument`.

## Requisitos

- Python 3.10+
- Bot de Telegram (token) y `chat_id`

Instala dependencias:

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install "mcp[cli]"
```

## Configurar Cursor

Crea `.cursor/mcp.json` en la raíz del repo:

```json
{
  "mcpServers": {
    "telegram": {
      "command": "/ABSOLUTE/PATH/TO/PROJECT/.venv/bin/python",
      "args": ["/ABSOLUTE/PATH/TO/PROJECT/mcp/telegram_mcp.py"],
      "env": {
        "TELEGRAM_BOT_TOKEN": "YOUR_BOT_TOKEN",
        "TELEGRAM_CHAT_ID": "YOUR_CHAT_ID",
        "TELEGRAM_ALLOWED_ROOT": "/ABSOLUTE/PATH/TO/PROJECT"
      }
    }
  }
}
```

Si tu Cursor/OS no soporta `env` en `mcp.json`, usa un wrapper:

```bash
#!/usr/bin/env bash
set -euo pipefail
export TELEGRAM_BOT_TOKEN="YOUR_BOT_TOKEN"
export TELEGRAM_CHAT_ID="YOUR_CHAT_ID"
exec /ABSOLUTE/PATH/TO/PROJECT/.venv/bin/python /ABSOLUTE/PATH/TO/PROJECT/mcp/telegram_mcp.py
```

Y en `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "telegram": {
      "command": "bash",
      "args": ["/ABSOLUTE/PATH/TO/PROJECT/run_telegram_mcp.sh"]
    }
  }
}
```

## Uso en Cursor

Ejemplo:

> Usa `send_document` para enviar `./ruta/al/archivo.pdf` a Telegram con caption `build artifacts`.

Tool expuesta: `send_document(file_path, caption?, chat_id?, disable_notification?, protect_content?)`.

## Variables de entorno

- `TELEGRAM_BOT_TOKEN`: token del bot.
- `TELEGRAM_CHAT_ID`: chat destino por defecto.
- `TELEGRAM_ALLOWED_ROOT`: raíz opcional para restringir rutas.
- `TELEGRAM_API_HOST`: host opcional, por defecto `api.telegram.org`.
