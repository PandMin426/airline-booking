package com.airlinebooking.booking.service.imp;

import com.airlinebooking.booking.exceptions.AppException;
import com.airlinebooking.booking.exceptions.ErrorCode;
import com.airlinebooking.booking.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisServiceImp implements RedisService {

    //vì setIfAbsent nhận phần thời gian dạng long
    @Value("${booking.hold-minutes}")
    private long holdMinutes = 15;
    // mặc định mỗi lần quét 100 keys
    private static final int QUANTITY_KEYS = 100;

    private final StringRedisTemplate redisTemplate;




    // hàm này
    @Override
    public boolean lockSeat(Integer flightId, Integer userId, String seatNumber) {
        // kết thành một định dạng chuỗi ban đầu định dạng
        // đó sẽ là key và value là id người dùng
        String key = "booking:flight:" + flightId + ":seat:" + seatNumber;
        String value = String.valueOf(userId);


        // sau đó sẽ check và lưu trên redis, lưu thành công trả true (và trên redis sẽ lưu key và value), và ngược lại
        Boolean isLocked = redisTemplate.opsForValue().setIfAbsent(key, value, holdMinutes, TimeUnit.MINUTES);

        //ở đây là kiểu trả về là Boolean nên có thẻ nhận null nên pha dùng so sánh
        return Boolean.TRUE.equals(isLocked);
    }

    @Override
    public boolean isSeatHoldByCurrentUser(Integer flightId, Integer userId, String seatNumber) {

        String key = "booking:flight:" + flightId + ":seat:" + seatNumber;
        String value = String.valueOf(userId);

        String userIdCurrent = redisTemplate.opsForValue().get(key);

        return  userIdCurrent != null && userIdCurrent.equals(value);
    }

    @Override
    public boolean unlockSeat(Integer flightId, Integer userId, String seatNumber) {

        String key = "booking:flight:" + flightId + ":seat:" + seatNumber;
        String value = String.valueOf(userId);


        // tạo một đoạn script đẻ redis khi thực hiện đoạn này sẽ khóa tất cả cuwar khác lại
        String luaScript =  """
                        if redis.call('get', KEYS[1]) == ARGV[1] then
                            return redis.call('del', KEYS[1])
                        else
                            return 0
                        end
                        """;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(luaScript);
        redisScript.setResultType(Long.class);

        Long result =  redisTemplate.execute(redisScript, List.of(key), value);

        return result != null && result == 1L;

    }

    @Override
    public Set<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keysFound = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();

            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keysFound.add(new String(cursor.next()));
                }
            } catch (Exception e) {
                log.error("Lỗi khi scan Redis với pattern: {}", pattern, e);
                throw new AppException(ErrorCode.REDIS_OPERATION_FAILED);
            }
            return keysFound;
        });
    }

    @Override
    public void extendSeatLock(Integer flightId, String seatNumber) {
        // 1. Chốt chặn an toàn (Guard clause) đem từ BookingService qua
        if (!StringUtils.hasText(seatNumber)) {
            return;
        }

        String key = "booking:flight:" + flightId + ":seat:" + seatNumber;

        // Sử dụng biến holdMinutes đã được khai báo ở đầu file RedisServiceImp
        Boolean isExtended = redisTemplate.expire(key, holdMinutes, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(isExtended)) {
            throw new AppException(ErrorCode.SEAT_HOLD_EXPIRED_OR_INVALID);
        }
    }

    @Override
    public void clearStaticSeatMapCache(Integer flightId) {
        String key = "booking:flight:" + flightId + ":static_seatmap";
        redisTemplate.delete(key);
        log.info("Đã xóa cache sơ đồ ghế tĩnh cho chuyến bay: {}", flightId);
    }


    @Override
    public List<String> getHeldSeats(Integer flightId) {
        String pattern = "booking:flight:" + flightId + ":seat:*";

        // Gọi hàm scanKeys ngay trong chính class này
        Set<String> keys = this.scanKeys(pattern);

        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }

        // Clean code: Dùng Stream để tách chuỗi thay vì vòng lặp for thủ công
        return keys.stream()
                .map(key -> {
                    String[] parts = key.split(":");
                    return parts[parts.length - 1]; // Lấy phần tử cuối cùng (seatNumber)
                })
                .toList(); // (Dùng .collect(Collectors.toList()) nếu bạn dùng Java cũ hơn 16)
    }
}
