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
    private static final ZoneId USER_TIMEZONE = ZoneId.of("Europe/Moscow");

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
        String openWeatherMapUrl = String.format("http://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s", encodedLocation, WEATHER_API_KEY);

        HttpResponse<String> response = sendHttpRequest(openWeatherMapUrl);

        if (response != null) {
            System.out.println("OpenWeatherMap Response Status Code: " + response.statusCode());
            System.out.println("OpenWeatherMap Response Body: " + response.body());

            if (response.statusCode() == 200) {
                JSONObject jsonObject = new JSONObject(response.body());
                return !jsonObject.has("message"); // Если сообщение ошибки отсутствует, место существует
            }
        }
        return false;
    }

    private String getWeatherInfo(String location) throws IOException, InterruptedException {
        if (!locationExists(location)) {
            return null;
        }

        String encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8);
        String weatherUrl = String.format("http://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s&units=metric&lang=ru",
                encodedLocation, WEATHER_API_KEY);
        HttpResponse<String> weatherResponse = sendHttpRequest(weatherUrl);

        if (weatherResponse != null && weatherResponse.statusCode() == 200) {
            JSONObject weatherJsonObject = new JSONObject(weatherResponse.body());
            double temperature = weatherJsonObject.getJSONObject("main").getDouble("temp");
            String weatherDescription = weatherJsonObject.getJSONArray("weather").getJSONObject(0).getString("description");

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime timeUtc = LocalDateTime.now(ZoneId.of("UTC"));

            LocalDateTime userTime = timeUtc.atZone(ZoneId.of("UTC")).withZoneSameInstant(USER_TIMEZONE).toLocalDateTime();
            String formattedTime = userTime.format(DateTimeFormatter.ofPattern("HH:mm"));

            String weatherInfo = String.format("Температура в городе %s (%s): %.1f°C, %s",
                    location, formattedTime, temperature, weatherDescription);

            weatherInfo += "\n\n" + getClothingRecommendation(temperature, weatherDescription);

            return weatherInfo;
        }
        return null;
    }

    private HttpResponse<String> sendHttpRequest(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void sendStartMessage(Long chatId) {
        String text = "Привет! Меня зовут MeteoMag, могу показать тебе погоду. Просто отправь мне название города.";
        sendMessage(chatId, text);
    }

    private void sendWeatherMessage(long chatId, String weatherInfo) {
        sendMessage(chatId, weatherInfo);
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
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
        StringBuilder recommendation = new StringBuilder();

        if (temperature >= 25) {
            recommendation.append("Жарко! ☀️ Наденьте легкую одежду: шорты, футболку или платье.");
        } else if (temperature >= 15) {
            recommendation.append("Тепло! 🌤️ Подойдут джинсы, футболка, легкая куртка.");
        } else if (temperature >= 5) {
            recommendation.append("Прохладно. 🍂 Наденьте свитер, куртку, джинсы.");
        } else {
            recommendation.append("Холодно! ❄️ Наденьте теплую куртку, шапку, шарф и перчатки.");
        }

        if (weatherDescription.contains("дождь")) {
            recommendation.append("\nНе забудьте зонт! ☔️");
        }

        return recommendation.toString();
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
