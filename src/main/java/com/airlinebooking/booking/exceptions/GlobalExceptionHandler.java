package com.airlinebooking.booking.exceptions;

import com.airlinebooking.booking.payload.response.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // bắt tất cả lỗi kế thừa từ AppException
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handAppException(AppException e){


        ErrorCode errorCode = e.getErrorCode();

        ErrorResponse errorResponse = new ErrorResponse(errorCode);

        return ResponseEntity.status(errorCode.getStatusCode()).body(errorResponse);
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnwantedException(Exception e){
        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.UNCATEGORIZED_EXCEPTION);


        log.error("Lỗi không xác định (Uncategorized Exception): ", e);



        return ResponseEntity.status(ErrorCode.UNCATEGORIZED_EXCEPTION.getStatusCode()).body(errorResponse);

    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e){
//        String message = e.getBindingResult().getFieldError().getDefaultMessage();
//
//        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.VALIDATION_ERROR, message);

        List<String> messageList = new ArrayList<>();

        for(FieldError fe : e.getBindingResult().getFieldErrors()){
            messageList.add(fe.getDefaultMessage());

        }



        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.VALIDATION_ERROR, messageList);

        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatusCode()).body(errorResponse);


    }

}
