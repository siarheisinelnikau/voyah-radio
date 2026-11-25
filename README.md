# Voyah Radio

**Языки интерфейса** — русский, английский, казахский · [English README](README_EN.md)

Приложение-компаньон для [Open Voyah Controls](https://github.com/siarheisinelnikau/open-voyah-controls). Интернет-радио для центрального экрана Voyah Free на Android 9 и новее.

- Слушайте станции Беларуси, России, Казахстана и других стран. Ищите по названию или частоте и сохраняйте любимые станции.
- Переключайте станции кнопками мультимедиа, слушайте в фоне и смотрите название трека, если станция передаёт его.
- Настраивайте порядок и видимость станций. Выбирайте графитовую или янтарную тему, спектр или звуковую волну.

![Voyah Radio station browser on a landscape Android display](docs/screenshots/radio-main.png)
![Voyah Radio settings for language, appearance, and station visibility](docs/screenshots/settings.png)

## Сборка

Для сборки и тестов нужны JDK 17 или новее и Android SDK 35. Запустите `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Приложение использует один Gradle-модуль и один Activity. Интерфейс Jetpack Compose получает состояние из Kotlin ViewModel, правила каталога выделены в слой domain, а воспроизведение работает в Android-сервисе.

[GPLv3](LICENSE)
