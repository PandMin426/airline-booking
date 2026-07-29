package com.airlinebooking.booking.service.imp;

import com.airlinebooking.booking.entity.SeatEntity;
import com.airlinebooking.booking.exceptions.AppException;
import com.airlinebooking.booking.exceptions.ErrorCode;
import com.airlinebooking.booking.mapper.SeatMapper;
import com.airlinebooking.booking.payload.response.SeatResponse;
import com.airlinebooking.booking.repository.SeatRepository;
import com.airlinebooking.booking.service.RedisService;
import com.airlinebooking.booking.service.SeatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SeatServiceImp implements SeatService {

    private static final long HELD_STATIC_SEAT_MAP = 15;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private SeatMapper seatMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<SeatResponse> getSeatMap(Integer flightId) {


        // 1: 5A CANCELLED --> HELD
        // 2: 5A --> HELD



        // mapper snag
        List<SeatResponse> seatResponseList = new ArrayList<>();
        seatResponseList = getStaticSeatMap(flightId);


        // sau đó tìm các ghế đang bị khóa (HELD) trên redis
        String pattern = "booking:flight:" + flightId + ":seat:*";

        Set<String> heldSeatKeys = redisService.scanKeys(pattern);


        // sau đó mình quét các chuyến bay lấy từ db, nếu phần ghế nào đang trùng thì tức laf ghế đó đnag bị held
        for(SeatResponse s : seatResponseList){
            String patternOfList = "booking:flight:" + flightId + ":seat:" + s.getSeatNumber();


            // lấy phần danh sách trên redis có tồn tại phần dưới db không
            if(heldSeatKeys.contains(patternOfList)){


                s.setStatus("HELD");
            }

        }
        



        return seatResponseList;









    }




    // Lấy danh sách ở trên redis hay ở db trước(nếu lấy db thì phải lưu lên lại redis lần sau dùng tiếp)
    private List<SeatResponse> getStaticSeatMap(Integer flightId){
        List<SeatResponse> seatResponseList = new ArrayList<>();
        String staticSeatMapKeys = "booking:flight:" + flightId + ":static_seatmap";

        try {
            //check redis trước coi thử có không
            String cachedData = stringRedisTemplate.opsForValue().get(staticSeatMapKeys);

            if(cachedData != null){
                seatResponseList = objectMapper.readValue(cachedData, new TypeReference<List<SeatResponse>>() {});

                log.info("TRÊN REDIS CÓ, LẤY TỪ ĐÓ XUỐNG");
            } else {

                System.out.println("lấy dưới db");
                // đầu tiên sẽ lấy danh sách ghế dưới db
                List<SeatEntity> seatEntityList = new ArrayList<>();

                seatEntityList = seatRepository.findByFlight_FlightId(flightId);


                // mapper snag
                for(SeatEntity s : seatEntityList){
                    SeatResponse seatResponse = seatMapper.toResponse(s);

                    seatResponseList.add(seatResponse);
                }

                // lưu kết quả lên redis
                String jsonToCache = objectMapper.writeValueAsString(seatResponseList);
                stringRedisTemplate.opsForValue().set(staticSeatMapKeys, jsonToCache, HELD_STATIC_SEAT_MAP, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            throw new AppException(ErrorCode.REDIS_OPERATION_FAILED);

//            if(seatResponseList.isEmpty()){
//                System.out.println("gọi db");
//
//                List<SeatEntity> seatEntityList = new ArrayList<>();
//
//                seatEntityList = seatRepository.findByFlight_FlightId(flightId);
//
//
//                // mapper snag
//                for(SeatEntity s : seatEntityList){
//                    SeatResponse seatResponse = seatMapper.toResponse(s);
//
//                    seatResponseList.add(seatResponse);
//                }
//            }
        }

        return seatResponseList;
    }
}
