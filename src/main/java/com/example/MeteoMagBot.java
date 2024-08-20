package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MeteoMagBot extends TelegramLongPollingBot {

    private static final Logger LOGGER = Logger.getLogger(MeteoMagBot.class.getName());

    @Override
    public String getBotUsername() {
        return "MeteoMag";
    }

    @Override
    public String getBotToken() {
        return System.getenv("TELEGRAM_BOT_TOKEN");
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (messageText.equals("/start")) {
                sendResponse(chatId, "Привет! Я бот MeteoMag. Введи название населенного пункта, чтобы узнать текущую погоду.");
            } else if (messageText.equals("/help")) {
                sendResponse(chatId, "Доступные команды:\n/start - Начать работу с ботом\n/help - Справка по командам\nВведи название населенного пункта, чтобы узнать погоду.");
            } else {
                handleWeatherRequest(chatId, messageText);
            }
        }
    }

    private void sendResponse(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            LOGGER.log(Level.SEVERE, "Ошибка при отправке сообщения", e);
        }
    }

    private void handleWeatherRequest(long chatId, String location) {
        try {
            OkHttpClient client = new OkHttpClient();
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + location + "&count=1&language=ru&format=json";
            Request geoRequest = new Request.Builder().url(geoUrl).build();
            Response geoResponse = client.newCall(geoRequest).execute();
            String geoResponseBody = geoResponse.body().string();

            JSONObject geoJson = new JSONObject(geoResponseBody);
            JSONArray results = geoJson.getJSONArray("results");

            if (results.length() > 0) {
                JSONObject result = results.getJSONObject(0);

                if (!isValidLocation(location)) {
                    sendResponse(chatId, "Пожалуйста, введи название населенного пункта.");
                    return;
                }

                double latitude = result.getDouble("latitude");
                double longitude = result.getDouble("longitude");

                String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude +
                        "&hourly=temperature_2m,weather_code,wind_speed_10m,uv_index&wind_speed_unit=ms&forecast_hours=6";
                Request weatherRequest = new Request.Builder().url(weatherUrl).build();
                Response weatherResponse = client.newCall(weatherRequest).execute();
                String weatherResponseBody = weatherResponse.body().string();

                JSONObject weatherJson = new JSONObject(weatherResponseBody);
                JSONObject currentWeather = weatherJson.getJSONObject("hourly");

                // Извлечение данных
                double temperature = currentWeather.getJSONArray("temperature_2m").getDouble(0);
                int weatherCode = currentWeather.getJSONArray("weather_code").getInt(0);
                double windSpeed = currentWeather.getJSONArray("wind_speed_10m").getDouble(0);
                double uvIndex = currentWeather.getJSONArray("uv_index").getDouble(0);

                String weatherDescription = translateWeatherCode(weatherCode);
                String recommendation = getClothingRecommendation(temperature, weatherDescription);

                sendResponse(chatId, String.format(
                        "Текущая температура в \"%s\" составляет %.1f°C. Погодные условия: %s " +
                                "Скорость ветра: %.1f м/с. \n%s\n\nУровень ультрафиолета: %.1f. %s",
                        location, temperature, weatherDescription, windSpeed,
                        recommendation, uvIndex, getUVProtectionRecommendation(uvIndex)));
            } else {
                sendResponse(chatId, "Населенный пункт не найден. Пожалуйста, проверь ввод.");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Ошибка при обработке запроса погоды", e);
            sendResponse(chatId, "Ошибка при получении данных о погоде. Проверь ввод или попробуй позже.");
        }
    }

    private boolean isValidLocation(String location) throws IOException {
        // Инициализация OkHttpClient
        OkHttpClient client = new OkHttpClient();
        // Запрос для получения данных о географическом объекте
        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + location + "&count=1&language=ru&format=json";
        Request geoRequest = new Request.Builder().url(geoUrl).build();
        Response geoResponse = client.newCall(geoRequest).execute();
        String geoResponseBody = geoResponse.body().string();

        // Логирование ответа от Geo API для диагностики
        LOGGER.info("Geo API Response: " + geoResponseBody);

        JSONObject geoJson = new JSONObject(geoResponseBody);
        JSONArray results = geoJson.getJSONArray("results");

        // Список допустимых значений feature_code для населенных пунктов
        Set<String> validFeatureCodes = Set.of(
                "PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLC",
                "PPLCH", "PPLF", "PPLG", "PPLH", "PPLL", "PPLQ", "PPLR",
                "PPLS", "PPLW", "PPLX", "STLMT"
        );

        if (results.length() > 0) {
            JSONObject result = results.getJSONObject(0);
            if (result.has("feature_code")) {
                String featureCode = result.getString("feature_code");
                return validFeatureCodes.contains(featureCode);
            } else {
                LOGGER.warning("Feature code not found in the response.");
            }
        }
        return false;
    }

    private String getUVProtectionRecommendation(double uvIndex) {
        if (uvIndex < 3) {
            return "\nНизкий уровень UV \uD83D\uDFE2 Меры предосторожности минимальны. Можешь безопасно находиться на улице без дополнительной защиты.";
        } else if (uvIndex < 6) {
            return "\nУмеренный уровень UV \uD83D\uDFE1 Рекомендуется использовать солнцезащитный крем с SPF 30+. По желанию головной убор или солнцезащитные очки.";
        } else if (uvIndex < 8) {
            return "\nВысокий уровень UV \uD83D\uDFE0 Используй солнцезащитный крем с SPF 50+. Избегай прямого солнечного света в полуденные часы. Надевай головной убор и солнцезащитные очки.";
        } else if (uvIndex < 11) {
            return "\nОчень высокий уровень UV! \uD83D\uDD34 Используй солнцезащитный крем с SPF 50+ и выше. По возможности оставайся в помещении с 11 до 15 часов дня, на улице ищи тень. Надевай головной убор и солнцезащитные очки.";
        } else {
            return "\nЭкстремальный уровень UV! \uD83D\uDFE3 Используй крем с SPF 50+ и выше. По возможности избегай нахождения на улице или находись в тени. Надевай головной убор и солнцезащитные очки.";
        }
    }

    private String translateWeatherCode(int code) {
        switch (code) {
            case 0:
                return "ясное небо ☀️";
            case 1:
                return "в основном ясно 🌤️";
            case 2:
                return "переменная облачность ⛅";
            case 3:
                return "пасмурно ☁️";
            case 45:
                return "туман 🌫️";
            case 48:
                return "осаждающий иней ❄️";
            case 51:
                return "морось, лёгкая 🌦️";
            case 53:
                return "морось, умеренная 🌧️";
            case 55:
                return "морось, густая 🌧️🌧️";
            case 56:
                return "ледяная морось, лёгкая 🌨️❄️️";
            case 57:
                return "ледяная морось, густая ❄️";
            case 61:
                return "дождь, слабый 🌧️";
            case 63:
                return "дождь, умеренный 🌧️🌧️";
            case 65:
                return "дождь, сильный 🌧️🌧️🌧️";
            case 66:
                return "ледяной дождь, лёгкий 🌨️❄️";
            case 67:
                return "ледяной дождь, сильный 🌨️🌨️❄️";
            case 71:
                return "снегопад, слабый ❄️";
            case 73:
                return "снегопад, умеренный ❄️❄️";
            case 75:
                return "снегопад, сильный ❄️❄️❄️";
            case 77:
                return "снежные зерна ❄️";
            case 80:
                return "ливни, слабые 🌧️";
            case 81:
                return "ливни, умеренные 🌧️️🌧️";
            case 82:
                return "ливни, сильные 🌧️🌧️🌧️";
            case 85:
                return "снегопады, слабые ❄️";
            case 86:
                return "снегопады, сильные ❄️❄️❄️️️️️";
            case 95:
                return "гроза, слабая или умеренная ⛈️";
            case 96:
                return "гроза с градом, слабая ⛈️⚡";
            case 99:
                return "гроза с градом, сильная ⛈️⚡⚡";
            default:
                return "неизвестные погодные условия ❓";
        }
    }

    private String getClothingRecommendation(double temperature, String weatherDescription) {
        StringBuilder recommendation = new StringBuilder();

        if (temperature >= 24) {
            recommendation.append("\nВыбери лёгкую одежду: майка или футболка + шорты или лёгкие брюки.");
        } else if (temperature >= 15) {
            recommendation.append("\nПодойдет футболка или рубашка + джинсы или брюки. Можешь взять с собой лёгкую куртку.");
        } else if (temperature >= 5) {
            recommendation.append("\nОденься теплее, толстовка или свитшот + джинсы или брюки + пальто или куртка.");
        } else if (temperature >= -5) {
            recommendation.append("\nЧтобы не замерзнуть подойдет свитер + тёплая куртка или пальто + шапка (по желанию).");
        } else if (temperature >= -15) {
            recommendation.append("\nХолодно, лучше выбрать тёплую одежду: вязанный свитер, зимняя обувь, зимняя куртка/дубленка, шапка, перчатки, шарф.");
        } else {
            recommendation.append("\nДубак!\uD83E\uDD76 Доставай самую тёплую одежду: вязанный свитер, зимняя обувь, тулуп или тёплая куртка с мехом. Обязательно шапка, перчатки и шарф.");
        }

        if (weatherDescription.contains("дождь") || weatherDescription.contains("ливни") || weatherDescription.contains("гроза") || weatherDescription.contains("морось")) {
            recommendation.append(" Захвати зонт и надень резиновую обувь☔");
        }

        if (weatherDescription.toLowerCase().contains("снег")) {
            recommendation.append(" Осторожнее на дорогах! Не прибегай к резкому торможению для избежания заноса.");
        } else if (weatherDescription.toLowerCase().contains("туман")) {
            recommendation.append(" Будь осторожен на дороге из-за плохой видимости!");
        }

        return recommendation.toString();
    }

    public static void main(String[] args) {
        try {
            // Инициализация API Telegram Bots
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MeteoMagBot());
            System.out.println("MeteoMagBot успешно запущен!");
        } catch (TelegramApiException e) {
            LOGGER.log(Level.SEVERE, "Ошибка при запуске бота", e);
        }
    }
}
