# Available Endpoints from the Backend Server

| Endpoint | Methods | Description | Example |
|----------|---------|-------------|----------|
| `/` | GET | This page - Server information and API documentation | N/A |
| `/health` | GET | Health check endpoint | N/A |
| `/transcribe` | POST | Transcribe audio to text | `curl -X POST -F "audio=@your-audio-file.wav" http://localhost:5000/transcribe` |
| `/translate` | POST | Translate text using the configured LLM | `curl -X POST -H "Content-Type: application/json" -d '{"text": "your text to translate"}' http://localhost:5000/translate` |
| `/command` | POST | Execute a command | `curl -X POST -H "Content-Type: application/json" -d '{"command": "click on Firefox", "capture_screenshot": true}' http://localhost:5000/command` |
| `/voice-command` | POST | Process and execute a voice command | `curl -X POST -F "audio=@your-command.wav" http://localhost:5000/voice-command` |
| `/screenshots` | GET | List available screenshots | `curl http://localhost:5000/screenshots` |
| `/screenshots/latest` | GET | Get information about the latest screenshots | `curl http://localhost:5000/screenshots/latest` |
| `/screenshots/` | GET | Serve a specific screenshot file | `curl http://localhost:5000/screenshots/ocr_screenshot.png > screenshot.png` |
| `/screenshots/view` | GET | View screenshots in a simple HTML page | Open `http://localhost:5000/screenshots/view` in your browser |
| `/screenshot/capture` | GET, POST | Capture a screenshot on demand | `curl -X POST -H "Content-Type: application/json" http://localhost:5000/screenshot/capture?format=json` |