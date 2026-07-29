package com.airlinebooking.booking.consumer;

import com.airlinebooking.booking.service.BookingService;
import com.airlinebooking.booking.service.RedisService;
import com.airlinebooking.notification.service.EmailService;
import com.airlinebooking.payment.event.PaymentSuccessEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingKafkaConsumerService {
    private final RedisService redisService;

    private final ObjectMapper objectMapper;

    private final BookingService bookingService;

    private final EmailService emailService;

    @KafkaListener(topics = "booking-payment-completed-topic", groupId = "airline-booking-group")
    public void listen(String message){
        log.info("KAFKA CONSUMER: Nhận được tin nhắn: {}", message);

        try{
            PaymentSuccessEvent event = objectMapper.readValue(message, PaymentSuccessEvent.class);

            if(event.getBookingId() == null || !event.getStatus().equals("SUCCESS")){
                log.warn("Tin nhắn không hợp lệ hoặc trạng thái không phải SUCCESS. Bỏ qua!");
                return;
            }

            bookingService.unlockSeatsByBookingId(event.getBookingId());

            // THIẾU XÓA STATIC_MAP TRÊN REDIS



            String customerEmail = bookingService.getEmailByBookingId(event.getBookingId());
            emailService.sendPaymentSuccessEmail(customerEmail, event.getBookingId());

            log.info("KAFKA CONSUMER: Xử lý thành công cho Booking ID: {}", event.getBookingId());


        } catch (Exception e){
            log.error("KAFKA CONSUMER LỖI CRITICAL: Xử lý thất bại tin nhắn: {}", message, e);
        }




    }


    private Integer extractBookingId(String message){
        try{
            // đọc chuỗi message thành 1 câu Json
            JsonNode jsonNode = objectMapper.readTree(message);


            //Laays ra gias trij coojt boooking id
            Integer value = jsonNode.get("bookingId").asInt();

            return value;


        } catch (Exception e) {
            log.error("Lỗi khi parse tin nhắn Kafka: {}", message, e);
            return null;
        }
    }

}
