package com.example.talktoagent;
import com.google.gson.*;
import java.nio.file.*;
final class AuthorizationVectors {
    static JsonObject load() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of(System.getProperty("authorizationVectors")))).getAsJsonObject();
    }
}
