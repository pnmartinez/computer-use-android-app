# Guía de Construcción y Despliegue de APKs Android con MCP Telegram

## Resumen
Esta guía documenta el proceso completo para construir una aplicación Android y enviarla por Telegram usando el servidor MCP de Telegram, incluyendo la resolución de problemas comunes de configuración.

## Requisitos Previos
- Proyecto Android con Gradle
- JDK instalado
- MCP Server de Telegram configurado
- Token de bot de Telegram
- Chat ID de destino

## Proceso de Construcción del APK

### 1. Construir la Aplicación
```bash
# Desde el directorio raíz del proyecto Android
./gradlew assemble
```

**Salida esperada:**
```
BUILD SUCCESSFUL in 22s
77 actionable tasks: 25 executed, 52 up-to-date
```

### 2. Localizar el APK Generado
Los APKs se generan en:
```
app/build/outputs/apk/release/app-release-unsigned.apk  # Versión release (recomendada)
app/build/outputs/apk/debug/app-debug.apk              # Versión debug
```

**Verificación:**
```bash
ls -la app/build/outputs/apk/release/
# -rw-rw-r-- 1 user user 7815512 dic 26 20:23 app-release-unsigned.apk
```

## Configuración del Servidor MCP Telegram

### Variables de Entorno Requeridas
El archivo `.env` debe contener:

```env
TELEGRAM_BOT_TOKEN=8558370422:AAEFb0yoCcnp7bYuXaz4jbZFpwTEEPCMUnw
TELEGRAM_CHAT_ID=-464025185
TELEGRAM_ALLOWED_ROOT=/home/nava/Descargas
```

### Variables de Entorno Explicadas
- `TELEGRAM_BOT_TOKEN`: Token del bot de Telegram obtenido de @BotFather
- `TELEGRAM_CHAT_ID`: ID del chat/grupo donde enviar archivos
- `TELEGRAM_ALLOWED_ROOT`: **CRÍTICO** - Directorio raíz permitido para acceder archivos

### Ubicación del Servidor MCP
```
/home/nava/Documentos/Cline/MCP/telegram-mcp/
├── .env                    # Variables de entorno
├── build/index.js          # Servidor compilado
├── package.json           # Dependencias
└── README.md             # Documentación del proyecto
```

## Problemas Comunes y Soluciones

### Error: "No existe el archivo"
**Síntoma:** El MCP reporta que no puede encontrar archivos que sí existen.

**Causa:** La variable `TELEGRAM_ALLOWED_ROOT` está mal configurada.

**Solución:**
```bash
# Verificar configuración actual
cat /home/nava/Documentos/Cline/MCP/telegram-mcp/.env

# Corregir si es necesario
echo "TELEGRAM_ALLOWED_ROOT=/home/nava/Descargas" >> /home/nava/Documentos/Cline/MCP/telegram-mcp/.env

# Reiniciar el servidor MCP en Cursor
```

### Error: "Not connected"
**Causa:** El servidor MCP no está ejecutándose.

**Solución:**
1. Reiniciar el MCP server desde Cursor
2. Verificar que el proceso esté corriendo:
```bash
ps aux | grep telegram-mcp
```

### Error: Ruta incorrecta
**Causa:** Usando rutas absolutas en lugar de relativas al `TELEGRAM_ALLOWED_ROOT`.

**Solución:** Usar rutas relativas desde el directorio permitido.
```javascript
// ❌ Incorrecto
file_path: "/home/nava/Descargas/computer-use-android-app/app/build/outputs/apk/release/app-release-unsigned.apk"

// ✅ Correcto (desde TELEGRAM_ALLOWED_ROOT=/home/nava/Descargas)
file_path: "computer-use-android-app/app/build/outputs/apk/release/app-release-unsigned.apk"
```

## Método Alternativo: API Directa de Telegram

Si el MCP tiene problemas, usar curl directamente:

```bash
curl -X POST "https://api.telegram.org/bot{BOT_TOKEN}/sendDocument" \
  -F "chat_id={CHAT_ID}" \
  -F "document=@{RUTA_AL_APK}" \
  -F "caption=APK de la aplicación Android - versión release sin firmar"
```

**Respuesta exitosa:**
```json
{
  "ok": true,
  "result": {
    "document": {
      "file_name": "app-release-unsigned.apk",
      "file_size": 7815512
    }
  }
}
```

## Configuración del Proyecto Android

### Archivo build.gradle.kts (Módulo app)
```kotlin
plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
```

## Comandos Útiles

### Construcción
```bash
./gradlew clean          # Limpiar build anterior
./gradlew assemble       # Construir todas las variantes
./gradlew assembleRelease # Construir solo release
```

### Verificación de Archivos
```bash
find app/build -name "*.apk" -type f  # Encontrar todos los APKs
ls -lh app/build/outputs/apk/release/ # Ver detalles del APK release
```

### Gestión del Servidor MCP
```bash
# Verificar estado
ps aux | grep telegram-mcp

# Ver configuración
cat /home/nava/Documentos/Cline/MCP/telegram-mcp/.env

# Reiniciar (desde Cursor/Claude)
# Settings → MCP Servers → Restart github.com/User/telegram-mcp
```

## Mejores Prácticas

1. **Siempre verificar TELEGRAM_ALLOWED_ROOT** antes de enviar archivos
2. **Usar rutas relativas** desde el directorio permitido
3. **Reiniciar el MCP server** después de cambios en .env
4. **Verificar el tamaño del APK** antes de enviar (límite de Telegram: ~2GB)
5. **Usar la versión release** para distribución final
6. **Firmar el APK** antes de distribuirlo (opcional para testing)

## Solución de Problemas Avanzada

### Logs del Servidor MCP
```bash
cd /home/nava/Documentos/Cline/MCP/telegram-mcp
node build/index.js  # Ejecutar con output visible
```

### Verificar Conectividad de Red
```bash
curl "https://api.telegram.org/bot{BOT_TOKEN}/getMe"
```

### Verificar Permisos de Archivos
```bash
ls -la app/build/outputs/apk/release/app-release-unsigned.apk
# Debe tener permisos de lectura
```

## Conclusión

El proceso de construcción y despliegue de APKs Android con MCP Telegram requiere:
1. Configuración correcta de `TELEGRAM_ALLOWED_ROOT`
2. Reinicio del servidor MCP después de cambios
3. Uso de rutas relativas desde el directorio permitido
4. Verificación de permisos y conectividad

Siguiendo esta guía, se pueden automatizar los despliegues de aplicaciones Android de manera eficiente y segura.

---
**Última actualización:** Diciembre 2025
**Versión MCP:** github.com/User/telegram-mcp
**Proyecto:** computer-use-android-app
