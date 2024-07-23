package com.example;

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

    private final Map<String,String> weatherDescriptions = new HashMap<>();

    public MeteoMagBot() {
        // Заполняем словарь переводов для описаний погоды
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
        return "MeteoMag";
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            if (messageText.equals("/start")) {
                sendStartMessage(update.getMessage().getChatId());
            } else {
                String location = messageText.startsWith("/weather ") ? messageText.substring(9) : messageText;
                try {
                    String weatherInfo = getWeatherInfo(location);
                    sendWeatherMessage(update.getMessage().getChatId(), weatherInfo);
                } catch (IOException | InterruptedException e) {
                    sendWeatherMessage(update.getMessage().getChatId(), "Не удалось получить данные о погоде. Попробуйте позже.");
                    e.printStackTrace();
                }
            }
        }
    }

    private String getWeatherInfo(String location) throws IOException, InterruptedException {
        String url = String.format("https://api.weatherbit.io/v2.0/current?city=%s&key=%s&lang=ru",
                URLEncoder.encode(location, StandardCharsets.UTF_8), WEATHER_API_KEY);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to get weather info: " + response.body());
        }

        JSONObject jsonObject = new JSONObject(response.body());
        JSONObject data = jsonObject.getJSONArray("data").getJSONObject(0);
        double temperature = data.getDouble("temp");
        JSONObject weather = data.getJSONObject("weather");
        String weatherDescription = translateWeatherDescription(weather.getString("description"));

        // Изменения:
        // 1. Используем LocalDateTime для парсинга времени
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime timeUtc = LocalDateTime.parse(data.getString("ob_time"), formatter);

        // 2. Устанавливаем часовой пояс пользователя
        ZoneId userTimeZone = ZoneId.of("Europe/Moscow");
        LocalDateTime userTime = timeUtc.atZone(ZoneId.of("UTC")).withZoneSameInstant(userTimeZone).toLocalDateTime();

        // 3. Форматируем время для вывода
        String formattedTime = userTime.format(DateTimeFormatter.ofPattern("HH:mm"));

        return String.format("Температура в городе %s (%s): %.1f°C, %s", location, formattedTime, temperature, weatherDescription);
    }

    private void sendStartMessage(Long chatId) {
        SendMessage startMessage = new SendMessage();
        startMessage.setChatId(String.valueOf(chatId));
        startMessage.setText("Привет! Я MeteoMag бот. Я могу показать тебе погоду в любом городе. Просто отправь мне название города или используй команду /weather <город>.");
        try {
            execute(startMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private String translateWeatherDescription(String englishDescription) {

        return weatherDescriptions.getOrDefault(englishDescription, englishDescription);
    }

    private void sendWeatherMessage(long chatId, String weatherInfo) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));
        sendMessage.setText(weatherInfo);
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
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