package com.airlinebooking.booking.service.imp;

import com.airlinebooking.booking.entity.*;
import com.airlinebooking.booking.exceptions.AppException;
import com.airlinebooking.booking.exceptions.ErrorCode;
import com.airlinebooking.booking.mapper.BookingMapper;
import com.airlinebooking.booking.payload.request.BookingRequest;
import com.airlinebooking.booking.payload.request.PassengerRequest;
import com.airlinebooking.booking.payload.response.BookingResponse;
import com.airlinebooking.booking.repository.*;
import com.airlinebooking.booking.service.BookingService;

import com.airlinebooking.booking.service.RedisService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;

import java.util.*;

@Service
@AllArgsConstructor
@Slf4j
public class BookingServiceImp implements BookingService {
    private static final long TIME_TO_EXTEND_SEAT_LOCK = 15;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private BaggageCatalogRepository baggageCatalogRepository;

    @Autowired
    private PassengerTicketRepository passengerTicketRepository;

    @Autowired
    private TicketBaggageRepository ticketBaggageRepository;


    private final RedisService redisService;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PassengerRepository passengerRepository;

    @Autowired
    private BookingMapper bookingMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Transactional(rollbackFor = Exception.class)
    @Override
    public BookingResponse createBooking(BookingRequest bookingRequest, Integer userId) {

        //check ghế trùng trong cùng 1 booking
        List<String> runSeatNumber = new ArrayList<>();
        List<String> returnSeatNumber = new ArrayList<>();

        for(PassengerRequest p : bookingRequest.getPassengerRequestList()){
            if("INFANT".equals(p.getPassengerType())){
                continue;
            }

            if(p.getRunSeatNumber() != null){
                runSeatNumber.add(p.getRunSeatNumber());
            }

            if(p.getReturnSeatNumber() != null){
                returnSeatNumber.add(p.getReturnSeatNumber());
            }

        }

        Set<String> uniqueRunSeatNumber = new HashSet<>(runSeatNumber);
        if(uniqueRunSeatNumber.size() < runSeatNumber.size() ){
            throw new AppException(ErrorCode.DUPLICATE_SEAT_IN_REQUEST);
        }

        Set<String> uniqueReturnSeatNumber = new HashSet<>(returnSeatNumber);
        if(uniqueReturnSeatNumber.size() < returnSeatNumber.size()){
            throw new AppException(ErrorCode.DUPLICATE_SEAT_IN_REQUEST);

        }


        // nếu có bất kì ghế nào hết hạn hoặc khng chính chủ thì bỏ, unclock hết toàn bộ
        validateAndRollbackSeats(bookingRequest, userId);


        // gia hạn ghế trên redis
        extendSeatLock(bookingRequest);

        //lấy thông tin chuyến bay ra tuwf db lên có đuược base_price
        FlightEntity flightRunEntity = flightRepository.findById(bookingRequest.getRunFlightId())
                .orElseThrow(() -> new AppException(ErrorCode.FLIGHT_NOT_FOUND));

        FlightEntity flightReturnEntity = null;
        if(bookingRequest.getReturnFlightId() != null){
            flightReturnEntity = flightRepository.findById(bookingRequest.getReturnFlightId())
                    .orElseThrow(() -> new AppException(ErrorCode.FLIGHT_NOT_FOUND));
        }

        // Lấy ghế đang bị held trên redis chiều đi
        List<String> heldRunSeats = getHeldSeatsFromRedis(bookingRequest.getRunFlightId());
        if(heldRunSeats.isEmpty()) {
            heldRunSeats.add("DUMMY_SEAT_TO_AVOID_SQL_ERROR");
        }

        // Lấy ghế đang bị held trên redis chiều về
        List<String> heldReturnSeats = new ArrayList<>();
        if(bookingRequest.getReturnFlightId() != null){
            heldReturnSeats = getHeldSeatsFromRedis(bookingRequest.getReturnFlightId());

            if(heldReturnSeats.isEmpty()) {
                heldReturnSeats.add("DUMMY_SEAT_TO_AVOID_SQL_ERROR");
            }
        }

        // Tạo mẫu booking ban đầu trạng thái PENDING
        BookingEntity bookingEntity = initPendingBooking(bookingRequest, userId);

        //xử lý thông tin hành khách (lưu thông tin hành khách xuống db + tính tổng tiền tất cả)
        BigDecimal totalAmoutAllPassenger = processAllPassenger(bookingRequest, bookingEntity, flightRunEntity, flightReturnEntity,
                                                                heldRunSeats, heldReturnSeats);

        // set giá tiền
        bookingEntity.setTotalAmount(totalAmoutAllPassenger);



        return bookingMapper.toResponse(bookingRepository.save(bookingEntity)) ;
    }

    @Transactional
    @Override
    public void unlockSeatsByBookingId(Integer bookingId) {
        BookingEntity bookingEntity = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));





        for(PassengerTicketEntity p : bookingEntity.getPassengerTicketEntityList()){

            SeatEntity seat = p.getSeat();
            String seatNumber = seat.getSeatNumber();
            Integer flightId = seat.getFlight().getFlightId();

            // Nhả ghế trên redis
            boolean isUnlocked = redisService.unlockSeat(flightId, bookingEntity.getUserId(), seatNumber);

            int changeRows = seatRepository.releaseSeat(seat.getSeatId());

            if(isUnlocked && changeRows != 0){

                log.info("Giải phóng ghế {} thành công (xóa redis & update db)", seatNumber);


            }
        }


        // 3. [THÊM MỚI] Xóa Cache tĩnh sơ đồ ghế của chuyến bay này (để client gọi API map thấy ghế đã trống lại)
        // Giả sử key cache sơ đồ ghế của bạn là "seatmap:flight:{flightId}" (bạn tự chỉnh theo thực tế)
        if (!bookingEntity.getPassengerTicketEntityList().isEmpty()) {
            Integer flightId = bookingEntity.getPassengerTicketEntityList().get(0).getSeat().getFlight().getFlightId();
            stringRedisTemplate.delete("booking:flight:" + flightId + ":static_seatmap");
        }




    }

    @Override
    public String getEmailByBookingId(Integer bookingId) {

        Optional<String> email = bookingRepository.getContactEmailByBookingId(bookingId);
        if(!email.isPresent()){
            throw new AppException(ErrorCode.BOOKING_NOT_FOUND);// có thể sửa lại not found enail
        }
        return email.get();
    }

    @Override
    public List<BookingResponse> getMyBookingsByUserId(Integer userId) {
        List<BookingEntity> bookingEntityList = bookingRepository.getBookingEntitiesByUserId(userId);

        List<BookingResponse> bookingResponseList = bookingMapper.toResponseList(bookingEntityList);


        return bookingResponseList;
    }


     // Nếu có bất kỳ ghế nào hết hạn/không chính chủ -> Unlock TOÀN BỘ ghế trong request và báo lỗi
    private void validateAndRollbackSeats(BookingRequest request, Integer userId) {

        for (PassengerRequest pRequest : request.getPassengerRequestList()) {
            if ("INFANT".equals(pRequest.getPassengerType())) continue; // Bỏ qua em bé

            // 1. KIỂM TRA CHIỀU ĐI
            String runSeat = pRequest.getRunSeatNumber();
            if (runSeat != null && !runSeat.trim().isEmpty()) {
                log.warn("Lỗi ở đây 1", runSeat);


                if (!redisService.isSeatHoldByCurrentUser(request.getRunFlightId(), userId, runSeat)) {

                    log.warn("Lỗi ghế {} chiều đi. Đang Rollback toàn bộ request", runSeat);

                    rollbackAllSeatsInRequest(request, userId); // Gọi hàm rollback toàn bộ
                    throw new AppException(ErrorCode.SEAT_HOLD_EXPIRED_OR_INVALID);
                }
            }

            // 2. KIỂM TRA CHIỀU VỀ (NẾU CÓ)
            if (request.getReturnFlightId() != null) {
                String returnSeat = pRequest.getReturnSeatNumber();

                if (returnSeat != null && !returnSeat.trim().isEmpty()) {
                    if (!redisService.isSeatHoldByCurrentUser(request.getReturnFlightId(), userId, returnSeat)) {
                        log.warn("Lỗi ghế {} chiều về. Đang Rollback toàn bộ request", returnSeat);

                        rollbackAllSeatsInRequest(request, userId); // Gọi hàm rollback toàn bộ


                        throw new AppException(ErrorCode.SEAT_HOLD_EXPIRED_OR_INVALID);
                    }
                }
            }
        }
    }


    //Hàm dọn dẹp: Đơn giản là quét lại toàn bộ Request ban đầu và gọi lệnh Unlock

    private void rollbackAllSeatsInRequest(BookingRequest request, Integer userId) {
        for (PassengerRequest pRequest : request.getPassengerRequestList()) {
            if ("INFANT".equals(pRequest.getPassengerType())) continue;

            // Nhả ghế chiều đi
            if(pRequest.getRunSeatNumber() != null) {
                redisService.unlockSeat(request.getRunFlightId(), userId, pRequest.getRunSeatNumber());

            }

            // Nhả ghế chiều về (nếu có)
            if((request.getReturnFlightId() != null) && (pRequest.getReturnSeatNumber() != null)) {
                redisService.unlockSeat(request.getReturnFlightId(), userId, pRequest.getReturnSeatNumber());

            }
        }
        log.info("ROLLBACK XONG: Đã dọn dẹp sạch sẽ các ghế của user {} trên Redis", userId);
    }

    // hàm gia hạn Redis, ở đây gia hạn 15p
    private void extendSeatLock(BookingRequest bookingRequest){

        for(PassengerRequest pRequest : bookingRequest.getPassengerRequestList()){
            if(!"INFANT".equals(pRequest.getPassengerType())){
                redisService.extendSeatLock(bookingRequest.getRunFlightId(), pRequest.getRunSeatNumber(), TIME_TO_EXTEND_SEAT_LOCK);
                // không cần check chuyến về, chiều đi có thì gia hạn là duwduwojc rồi
                if(bookingRequest.getReturnFlightId() != null){
                    redisService.extendSeatLock(bookingRequest.getReturnFlightId(), pRequest.getReturnSeatNumber(), TIME_TO_EXTEND_SEAT_LOCK);

                }
            }
        }

        return;

    }

    //khi trên client gửi request thì sẽ tạo mẫu booking ban đầu lưu tạm xuống db
    //nhưng lúc này chwua biết tổng số tiền, và trạng thái booking là pending
    private BookingEntity initPendingBooking(BookingRequest  bookingRequest, Integer userId){
        BookingEntity bookingEntity = new BookingEntity();

        bookingEntity.setBookingCode(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        bookingEntity.setUserId(userId);
        bookingEntity.setContactName(bookingRequest.getContactInfoRequest().getFullName());
        bookingEntity.setContactEmail(bookingRequest.getContactInfoRequest().getEmail());
        bookingEntity.setContactPhone(bookingRequest.getContactInfoRequest().getPhoneNumber());
        bookingEntity.setStatus("PENDING");
        bookingEntity.setTotalAmount(BigDecimal.ZERO);

        return bookingRepository.save(bookingEntity);   // lưu lúc này n trả lại booking id ã có bookingId theo thứ tự dưới database
    }

    //xử lý tính tổng + lưu thông tin passenger
    private BigDecimal processAllPassenger(BookingRequest request, BookingEntity bookingEntity, FlightEntity runFlightEntity, FlightEntity returnFlightEntity,
                                            List<String> runHeldSeats, List<String> returnHeldSeats){
        BigDecimal totalAmount = BigDecimal.ZERO;

        for(PassengerRequest pRequest : request.getPassengerRequestList()){
            // lưu thông tin của passenger
            PassengerEntity passengerEntity = saveInfoPassenger(pRequest);

            // chheck nếu là INFANT thì bỏ qua
            if("INFANT".equals(pRequest.getPassengerType())){
                continue;
            }

            //tính tổng tiền của 1 người cả chiềuddisi và chiều về(nếu có) và cộng tổng vào
            totalAmount = totalAmount.add(calculateTotalTicketPasssenger(bookingEntity, runFlightEntity, returnFlightEntity, pRequest,passengerEntity, runHeldSeats, returnHeldSeats));



        }

        return totalAmount;
    }

    //Lưu thông tin từng khách hàng
    private PassengerEntity saveInfoPassenger(PassengerRequest request){
        PassengerEntity passengerEntity = new PassengerEntity();

        passengerEntity.setFullName(request.getFullName());
        passengerEntity.setDateOfBirth(request.getDateOfBirth());
        passengerEntity.setGender(request.getGender());
        passengerEntity.setPassengerType(request.getPassengerType());


        return passengerRepository.save(passengerEntity);

    }

    private BigDecimal calculateTotalTicketPasssenger(BookingEntity booking, FlightEntity runFlightEntity, FlightEntity returnFlightEntity, PassengerRequest passengerRequest, PassengerEntity passenger,
                                                        List<String> runHeldSeats, List<String> returnHeldSeats){
        BigDecimal totalPricePassenger = BigDecimal.ZERO;

        //tính tiền chiều đi
        totalPricePassenger = totalPricePassenger.add(calculateTicket(booking, runFlightEntity, passengerRequest.getRunSeatNumber(), passengerRequest.getRunBaggageId(), passenger,
                                                                        runHeldSeats));

        //tính tiền chiều về nễu có
        if(returnFlightEntity != null){
            totalPricePassenger = totalPricePassenger.add(calculateTicket(booking, returnFlightEntity, passengerRequest.getReturnSeatNumber(), passengerRequest.getReturnBaggageId(), passenger,
                                                                        returnHeldSeats));

        }

        return totalPricePassenger;


    }


    private BigDecimal calculateTicket(BookingEntity booking, FlightEntity flight, String seatNumber, Integer baggageId, PassengerEntity passenger, List<String> heldSeats) {

        SeatEntity seat = new SeatEntity();

        BigDecimal ticketPrice = flight.getBasePrice();

        // th1: khách chủ động chọn ghế thì sẽ có giá khác
        if(seatNumber != null && !seatNumber.trim().isEmpty()){ // ở đây check truyền vào có null và có phải toàn dấu cách không
            seat = seatRepository.findByFlightIdAndSeatNumber(flight.getFlightId(), seatNumber)
                    .orElseThrow(() -> new AppException(ErrorCode.SEAT_NOT_FOUND));

            //tính giá vé thật
            ticketPrice = ticketPrice.multiply(seat.getPriceMultiplier());

        } else {// th2: là bỏ qua và không chọn ghế thì sẽ lấy giá Mặc định chuyens bay đó và vị trí ghees random theo hạng ghế thấp đến cao
            seat = seatRepository.findRandomSeat(flight.getFlightId(), heldSeats)
                    .orElseThrow(() -> new AppException(ErrorCode.SEAT_NOT_FOUND));

        }

        log.info("không lỗi 1");
        // chặn tránh race condition tại db
        int updateRows = seatRepository.bookSeatIfAvailable(seat.getSeatId());

        log.info("không lỗi 2 ");

        if(updateRows == 0){
            throw new AppException(ErrorCode.SEAT_ALREADY_LOCKED);
        }

        // đến bước coi hành lý giá bao hiêu để cộng gi tiền vào
        BaggageCatalogEntity baggageCatalog = null;
        if(baggageId != null){
            Optional<BaggageCatalogEntity> baggageCatalogEntity = baggageCatalogRepository.findById(baggageId);
            if(!baggageCatalogEntity.isPresent()){
                throw new AppException(ErrorCode.BAGGAGE_NOT_FOUND);
            }

            baggageCatalog = baggageCatalogEntity.get();

            ticketPrice = ticketPrice.add(baggageCatalog.getPrice());
        }


        // lưu các phần dữ liệu người đó vào db PassengerTicket
        PassengerTicketEntity passengerTicket = new PassengerTicketEntity();

        passengerTicket.setBooking(booking);
        passengerTicket.setPassenger(passenger);
        passengerTicket.setSeat(seat);
        passengerTicket.setTicketPrice(ticketPrice);
        passengerTicket = passengerTicketRepository.save(passengerTicket);

        // lưu dữ liệu vào bảng ticketBaggage để biết vé đó đi kém với hành lý nào
        if(baggageId != null){
            TicketBaggageEntity ticketBaggageEntity = new TicketBaggageEntity();
            ticketBaggageEntity.setTicket(passengerTicket);
            ticketBaggageEntity.setBaggageCatalog(baggageCatalog);
            ticketBaggageEntity = ticketBaggageRepository.save(ticketBaggageEntity);
        }


        return ticketPrice;
    }

    private List<String> getHeldSeatsFromRedis(Integer flightId) {
        // Tìm tất cả các key đang giữ ghế của chuyến bay này
        String pattern = "booking:flight:" + flightId + ":seat:*";
        Set<String> keys = redisService.scanKeys(pattern);

        List<String> heldSeats = new ArrayList<>();
        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                // Cắt chuỗi để lấy ra seatNumber ở cuối.
                // Vd: "booking:flight:1:seat:12A" -> Tách theo ":" rồi lấy phần tử cuối là "12A"
                String[] parts = key.split(":");
                heldSeats.add(parts[parts.length - 1]);
            }
        }
        return heldSeats;
    }


}
