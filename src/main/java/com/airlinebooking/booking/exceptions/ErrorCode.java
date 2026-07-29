package com.airlinebooking.booking.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Lỗi hệ thống không xác đinh!!!", HttpStatus.INTERNAL_SERVER_ERROR),


    // các lỗi cho luồng
    INVALID_PASSENGER_RULE(4001, "Sai luật hành khách (VD: Trẻ sơ sinh > Người lớn)", HttpStatus.BAD_REQUEST),
    SEAT_ALREADY_LOCKED(4002, "Ghế đã có người chọn hoặc đang bị khóa", HttpStatus.BAD_REQUEST),
    ONE_ADULT_ONLY_ONE_INFANT(4005, "Một người lớn chỉ được kèm 1 trẻ nhỏ", HttpStatus.BAD_REQUEST),
    SEAT_UNLOCK_FAILED(4006, "Không thể nhả ghế: Ghế đã hết hạn giữ chỗ hoặc bạn không có quyền nhả ghế này", HttpStatus.BAD_REQUEST),
    VALIDATION_ERROR(4000, "Dữ liệu đầu vào không hợp lệ", HttpStatus.BAD_REQUEST),


    SEAT_NOT_FOUND(4003, "Không tìm thấy ghế trên hệ thống", HttpStatus.NOT_FOUND),
    FLIGHT_NOT_FOUND(4004, "Chuyến bay không tồn tại", HttpStatus.NOT_FOUND),
    BAGGAGE_NOT_FOUND(4007, "Không tìm thấy mức hành lý này trong kho", HttpStatus.NOT_FOUND),
    BOOKING_NOT_FOUND(4008, "Không tìm thấy booking này", HttpStatus.BAD_REQUEST),
    SEAT_HOLD_EXPIRED_OR_INVALID(4009, "Ghế này đã hết hạn hoặc không hợp lệ", HttpStatus.BAD_REQUEST),
    DUPLICATE_SEAT_IN_REQUEST(40009, "Dữ liệu ghế booking bị trùng", HttpStatus.BAD_REQUEST),

    BOOKING_PROCESSING_FAILED(5001, "Lỗi khi tạo mã đặt chỗ, vui lòng thử lại", HttpStatus.INTERNAL_SERVER_ERROR),
    REDIS_OPERATION_FAILED(5002, "Lỗi kết nối Redis khi giữ ghế", HttpStatus.INTERNAL_SERVER_ERROR),
    REDIS_OPERATION_WHEN_SCAN(5003, "Lỗi khi quét dữ liệu trên redis", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus statusCode;

    ErrorCode(int code, String message, HttpStatus statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
