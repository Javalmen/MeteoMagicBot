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
                sendResponse(chatId, "Привет! Я бот MeteoMag. Введите название населенного пункта, чтобы узнать текущую погоду.");
            } else if (messageText.equals("/help")) {
                sendResponse(chatId, "Доступные команды:\n/start - Начать работу с ботом\n/help - Справка по командам\nВведите название населенного пункта, чтобы узнать погоду.");
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
                String featureCode = result.getString("feature_code");

                // Проверка, является ли название населенного пунктом
                if (!isValidLocation(location)) {
                    sendResponse(chatId, "Пожалуйста, введите название населенного пункта.");
                    return;
                }

                double latitude = result.getDouble("latitude");
                double longitude = result.getDouble("longitude");

                String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude + "&current_weather=true&hourly=temperature_2m";
                Request weatherRequest = new Request.Builder().url(weatherUrl).build();
                Response weatherResponse = client.newCall(weatherRequest).execute();
                String weatherResponseBody = weatherResponse.body().string();

                JSONObject weatherJson = new JSONObject(weatherResponseBody);
                JSONObject currentWeather = weatherJson.getJSONObject("current_weather");
                double temperature = currentWeather.getDouble("temperature");
                int weatherCode = currentWeather.getInt("weathercode");

                String weatherDescription = translateWeatherCode(weatherCode);
                String recommendation = getClothingRecommendation(temperature, weatherDescription);

                sendResponse(chatId, String.format("Текущая температура в \"%s\" составляет %.1f°C. \nПогодные условия: %s. %s",
                        location, temperature, weatherDescription, recommendation));
            } else {
                sendResponse(chatId, "Населенный пункт не найден. Пожалуйста, проверьте ввод.");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Ошибка при обработке запроса погоды", e);
            sendResponse(chatId, "Ошибка при получении данных о погоде. Попробуйте позже.");
        }
    }

    private JSONObject getWeatherData(String location) throws IOException {
        OkHttpClient client = new OkHttpClient(); // Инициализация OkHttpClient

        // Получение координат населенного пункта
        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + location + "&count=1&language=ru&format=json";
        Request geoRequest = new Request.Builder().url(geoUrl).build();
        Response geoResponse = client.newCall(geoRequest).execute();
        String geoResponseBody = geoResponse.body().string();

        // Логирование полного ответа для диагностики
        LOGGER.info("Geo API Response: " + geoResponseBody);

        JSONObject geoJson = new JSONObject(geoResponseBody);
        JSONArray results = geoJson.getJSONArray("results");

        if (results.length() > 0) {
            JSONObject result = results.getJSONObject(0);
            double latitude = result.getDouble("latitude");
            double longitude = result.getDouble("longitude");

            // Получение данных о текущей погоде
            String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude + "&current_weather=true&hourly=temperature_2m";
            Request weatherRequest = new Request.Builder().url(weatherUrl).build();
            Response weatherResponse = client.newCall(weatherRequest).execute();
            String weatherResponseBody = weatherResponse.body().string();

            // Логирование полного ответа для диагностики
            LOGGER.info("Weather API Response: " + weatherResponseBody);

            JSONObject weatherJson = new JSONObject(weatherResponseBody);
            return weatherJson.getJSONObject("current_weather");
        }
        return null;
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

            // Проверка наличия и значения feature_code
            if (result.has("feature_code")) {
                String featureCode = result.getString("feature_code");
                // Проверка, что feature_code соответствует допустимым значениям
                return validFeatureCodes.contains(featureCode);
            } else {
                LOGGER.warning("Feature code not found in the response.");
            }
        }
        return false;
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

        if (temperature >= 25) {
            recommendation.append("\nЖарко! Наденьте легкую одежду: шорты, футболку или платье.");
            if (weatherDescription.toLowerCase().contains("дождь") || weatherDescription.toLowerCase().contains("ливни") || weatherDescription.toLowerCase().contains("гроза")) {
                recommendation.append(" \nЗонт не помешает.☔");
            }
        } else if (temperature >= 15) {
            recommendation.append("\nНа улице тепло! Подойдут джинсы, футболка, легкая куртка.");
            if (weatherDescription.toLowerCase().contains("дождь") || weatherDescription.toLowerCase().contains("ливни") || weatherDescription.toLowerCase().contains("гроза")) {
                recommendation.append(" \nНе забудьте взять дождевик или зонт!☔");
            }
        } else if (temperature >= 5) {
            recommendation.append("\nПрохладно. Наденьте свитер, куртку, джинсы.");
            if (weatherDescription.toLowerCase().contains("дождь") || weatherDescription.toLowerCase().contains("ливни") || weatherDescription.toLowerCase().contains("гроза")) {
                recommendation.append(" \nСамое время взять зонт!☔");
            }
        } else {
            recommendation.append("\nХолодно. Наденьте теплую куртку, шапку, шарф и перчатки.");
            if (weatherDescription.toLowerCase().contains("дождь") || weatherDescription.toLowerCase().contains("ливни") || weatherDescription.toLowerCase().contains("гроза")) {
                recommendation.append(" \nОденьтесь потеплее, возьмите зонт!☔");
            }
        }

        if (weatherDescription.toLowerCase().contains("снег")) {
            recommendation.append(" \nОсторожнее на дорогах! Не прибегайте к резкому торможению для избежания заноса.");
        } else if (weatherDescription.toLowerCase().contains("туман")) {
            recommendation.append(" \nБудьте осторожны на дорогах из-за плохой видимости!");
        }

        return recommendation.toString();
    }


    public static void main(String[] args) {
        try {
            // Инициализация API Telegram Bots
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

            // Регистрация бота
            botsApi.registerBot(new MeteoMagBot());
            System.out.println("MeteoMagBot успешно запущен!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}