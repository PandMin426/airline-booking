package com.airlinebooking.booking.repository;

import com.airlinebooking.booking.entity.SeatEntity;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<SeatEntity, Integer> {

    // tìm tất cả ghế thuộc về một chuyến bay cụ thể
    List<SeatEntity> findByFlight_FlightId(Integer flightId);

    // tìm ghế khi khác đã chủ đọng chọn ghế (sẽ trả ra ghế đó ể tính phí)
    @Query("""
            SELECT s
            FROM SeatEntity s 
            WHERE s.flight.flightId = :flightId AND s.seatNumber = :seatNumber
            """)
    Optional<SeatEntity> findByFlightIdAndSeatNumber(
            @Param("flightId") Integer flightId,
            @Param("seatNumber") String seatNumber
    );


    // chọn 1 ghế random tron cùng 1 hạng ghế (lấy từ hạng ghế thấp đến cao)
    @Query(value = """
            SELECT *
            FROM seats s
            WHERE s.flight_id = :flightId
            AND s.seat_status = 'AVAILABLE'
            AND s.seat_number NOT IN (:heldSeats)
            AND s.seat_id NOT IN(
                        SELECT pt.seat_id
                        FROM passenger_tickets pt
                        JOIN bookings b ON pt.booking_id = b.booking_id
                        WHERE b.status != 'CANCELLED'
                        )
                        ORDER BY s.price_multiplier ASC, RAND() LIMIT 1
                        
                        
                                    
            """
            , nativeQuery = true
    )
    Optional<SeatEntity> findRandomSeat(@Param("flightId") Integer flightId, @Param("heldSeats") List<String> heldSeats);

    @Modifying
    @Query(
            """
                update SeatEntity s
                set s.seatStatus = "BOOKED"
                where s.seatId = :seatId
                and  s.seatStatus = "AVAILABLE"
                        
            """

    )
    int bookSeatIfAvailable(@Param("seatId") Integer seatId);


    @Modifying
    @Query("""
                UPDATE SeatEntity s 
                SET s.seatStatus = 'AVAILABLE' 
                WHERE s.seatId = :seatId

            """)
    int releaseSeat(@Param("seatId") Integer seatId);

}
