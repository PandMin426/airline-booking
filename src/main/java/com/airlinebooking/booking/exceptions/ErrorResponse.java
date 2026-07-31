package com.airlinebooking.booking.exceptions;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;


@Getter
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int code;
    private List<String> messages;

    public ErrorResponse(ErrorCode errorCode){
        this.timestamp = LocalDateTime.now();
        this.code = errorCode.getCode();
        this.messages = List.of(errorCode.getMessage());
    }

    public ErrorResponse(ErrorCode errorCode, String message){
        this.timestamp = LocalDateTime.now();
        this.code = errorCode.getCode();
        this.messages = List.of(errorCode.getMessage());

    }

    public ErrorResponse(ErrorCode errorCode, List<String> messages){
        this.timestamp = LocalDateTime.now();
        this.code = errorCode.getCode();
        this.messages = messages;
    }

    public ErrorResponse() {

    }
}
