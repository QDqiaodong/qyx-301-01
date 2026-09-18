package com.example.salon.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Configuration
public class DateTimeConfig {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(FORMATTER));
            builder.deserializerByType(LocalDateTime.class, new LocalDateTimeJsonDeserializer());
        };
    }

    public static class LocalDateTimeJsonDeserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context)
                throws IOException, JsonProcessingException {
            String value = parser.getText().trim();
            
            if (value.length() == 10) {
                return LocalDateTime.parse(value + "T00:00:00");
            } else if (value.contains("T")) {
                if (value.length() == 16) {
                    return LocalDateTime.parse(value + ":00");
                }
                try {
                    return LocalDateTime.parse(value);
                } catch (DateTimeParseException e) {
                    return LocalDateTime.parse(value.replace(" ", "T"));
                }
            } else {
                return LocalDateTime.parse(value, FORMATTER);
            }
        }
    }
}
