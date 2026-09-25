package com.airlinebooking.booking.service;

import java.util.List;
import java.util.Set;

public interface RedisService {
    //Hàm lock ghế khi mà được chọn
    public boolean lockSeat(Integer flightId, Integer userId, String seatNumber);

    //Hàm check xem ghế hiện tại có phải là chur nhân đó không (TH người đó refesh lại trang)
    public boolean isSeatHoldByCurrentUser(Integer flightID, Integer userId, String seatNumber);


    // hàm unclock ghế khi người dùng sau khi qua bước chọn thì lại chọn BACK QUAY LẠI không chọn ghế đó nữa
    public boolean unlockSeat(Integer flightId, Integer userId, String seatNumber);


    public Set<String> scanKeys(String pattern);



    void extendSeatLock(Integer flightId, String seatNumber);
    void clearStaticSeatMapCache(Integer flightId);


    List<String> getHeldSeats(Integer flightId);
}
