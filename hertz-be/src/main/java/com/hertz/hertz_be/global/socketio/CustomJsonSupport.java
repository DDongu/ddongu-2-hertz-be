package com.hertz.hertz_be.global.socketio;

import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class CustomJsonSupport extends JacksonJsonSupport {

    public CustomJsonSupport() {
        super(); // 내부 objectMapper 초기화
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    private static ObjectMapper createCustomObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
