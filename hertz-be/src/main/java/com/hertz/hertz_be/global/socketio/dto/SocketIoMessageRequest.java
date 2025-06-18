package com.hertz.hertz_be.global.socketio.dto;

public record SocketIoMessageRequest(
        Long roomId,
        String message
) {}