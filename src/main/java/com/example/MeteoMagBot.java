package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class MeteoMagBot extends TelegramLongPollingBot {

    private static final String BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String WEATHER_API_KEY = System.getenv("WEATHER_API_KEY");

    private final Map<String, String> weatherDescriptions = new HashMap<>();

    public MeteoMagBot() {
        weatherDescriptions.put("Thunderstorm", "Гроза");
        weatherDescriptions.put("Drizzle", "Морось");
        weatherDescriptions.put("Rain", "Дождь");
        weatherDescriptions.put("Snow", "Снег");
        weatherDescriptions.put("Clear", "Ясно");
        weatherDescriptions.put("Clouds", "Облачно");
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;
    }

    @Override
    public String getBotUsername() {
        return "MeteoMag"; // Замените на имя вашего бота
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (messageText.equals("/start")) {
                sendStartMessage(chatId);
            } else {
                String location = messageText.startsWith("/weather ") ? messageText.substring(9) : messageText;
                try {
                    String weatherInfo = getWeatherInfo(location);
                    if (weatherInfo != null) {
                        sendWeatherMessage(chatId, weatherInfo);
                    } else {
                        sendWeatherMessage(chatId, "Не удалось определить местоположение. Пожалуйста, уточните запрос.");
                    }
                } catch (IOException | InterruptedException e) {
                    sendWeatherMessage(chatId, "Не удалось получить данные о погоде. Попробуйте позже.");
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean locationExists(String location) throws IOException, InterruptedException {
        String encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8);
        String nominatimUrl = String.format("https://nominatim.openstreetmap.org/search?q=%s&format=json&limit=1", encodedLocation);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nominatimUrl))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String responseBody = response.body();
            // Проверяем, начинается ли ответ с '[' - признак валидного JSON массива
            if (responseBody.startsWith("[")) {
                var jsonArray = new JSONArray(responseBody); // Используем JSONArray для парсинга
                return jsonArray.length() > 0;
            } else {
                System.err.println("Nominatim вернул не JSON массив: " + responseBody);
                return false;
            }
        }
        return false;
    }

    private String getWeatherInfo(String location) throws IOException, InterruptedException {
        if (!locationExists(location)) {
            return null;
        }

        String encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8);
        String nominatimUrl = String.format("https://nominatim.openstreetmap.org/search?q=%s&format=json&limit=1",
                encodedLocation);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest nominatimRequest = HttpRequest.newBuilder()
                .uri(URI.create(nominatimUrl))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> nominatimResponse = client.send(nominatimRequest, HttpResponse.BodyHandlers.ofString());

        if (nominatimResponse.statusCode() == 200) {
            var jsonArray = new JSONArray(nominatimResponse.body());
            if (jsonArray.length() > 0) {
                JSONObject locationData = jsonArray.getJSONObject(0);
                double lat = locationData.getDouble("lat");
                double lon = locationData.getDouble("lon");

                // Используем координаты для запроса к Weatherbit API
                String weatherUrl = String.format("https://api.weatherbit.io/v2.0/current?lat=%.6f&lon=%.6f&key=%s&lang=ru",
                        lat, lon, WEATHER_API_KEY);
                HttpRequest weatherRequest = HttpRequest.newBuilder()
                        .uri(URI.create(weatherUrl))
                        .header("Accept", "application/json")
                        .build();

                HttpResponse<String> weatherResponse = client.send(weatherRequest, HttpResponse.BodyHandlers.ofString());

                if (weatherResponse.statusCode() == 200) {
                    // Парсинг ответа от Weatherbit API
                    JSONObject jsonObject = new JSONObject(weatherResponse.body());
                    JSONObject data = jsonObject.getJSONArray("data").getJSONObject(0);
                    double temperature = data.getDouble("temp");
                    JSONObject weather = data.getJSONObject("weather");
                    String weatherDescription = translateWeatherDescription(weather.getString("description"));

                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                    LocalDateTime timeUtc = LocalDateTime.parse(data.getString("ob_time"), formatter);

                    ZoneId userTimeZone = ZoneId.of("Europe/Moscow"); // Устанавливаем часовой пояс Москвы
                    LocalDateTime userTime = timeUtc.atZone(ZoneId.of("UTC")).withZoneSameInstant(userTimeZone).toLocalDateTime();

                    String formattedTime = userTime.format(DateTimeFormatter.ofPattern("HH:mm"));

                    String weatherInfo = String.format("Температура в городе %s (%s): %.1f°C, %s",
                            location, formattedTime, temperature, weatherDescription);

                    // Добавляем рекомендации по одежде:
                    weatherInfo += "\n\n" + getClothingRecommendation(temperature, weatherDescription);

                    return weatherInfo;
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    private void sendStartMessage(Long chatId) {
        SendMessage startMessage = new SendMessage();
        startMessage.setChatId(String.valueOf(chatId));
        startMessage.setText("Привет! Меня зовут MeteoMag, могу показать тебе погоду. Просто отправь мне название города.");
        try {
            execute(startMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendWeatherMessage(long chatId, String weatherInfo) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(weatherInfo);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private String translateWeatherDescription(String englishDescription) {
        return weatherDescriptions.getOrDefault(englishDescription, englishDescription);
    }

    private String getClothingRecommendation(double temperature, String weatherDescription) {
        String recommendation = "";

        if (temperature >= 25) {
            recommendation += "Жарко! ☀️ Наденьте легкую одежду: шорты, футболку или платье.";
        } else if (temperature >= 15) {
            recommendation += "Тепло! 🌤️  Подойдут джинсы, футболка, легкая куртка.";
        } else if (temperature >= 5) {
            recommendation += "Прохладно. 🍂  Наденьте свитер, куртку, джинсы.";
        } else {
            recommendation += "Холодно! ❄️ Наденьте теплую куртку, шапку, шарф и перчатки.";
        }

        if (weatherDescription.contains("дождь")) {
            recommendation += "\nНе забудьте зонт! ☔️";
        }

        return recommendation;
    }

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MeteoMagBot());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}