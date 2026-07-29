package com.airlinebooking.payment.service.imp;

import com.airlinebooking.booking.entity.BookingEntity;
import com.airlinebooking.booking.exceptions.AppException;
import com.airlinebooking.booking.exceptions.ErrorCode;
import com.airlinebooking.booking.repository.BookingRepository;
import com.airlinebooking.payment.config.VNPayConfig;
import com.airlinebooking.payment.producer.PaymentKafkaProducer;
import com.airlinebooking.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PaymentServiceImp implements PaymentService {


    // Dùng @Value để lôi các cấu hình từ application.yml vào biến
    @Value("${vnpay.tmnCode}")
    private String vnpTmnCode;

    @Value("${vnpay.hashSecret}")
    private String secretKey;

    @Value("${vnpay.payUrl}")
    private String vnpPayUrl;

    @Value("${vnpay.returnUrl}")
    private String vnpReturnUrl;




    private final BookingRepository bookingRepository;



    private final PaymentKafkaProducer paymentKafkaProducer;

    @Override
    public String createVnPayPaymentUrl(Integer bookingId, String ipAddress) {

        // Bước 1: Đi tìm Booking dưới Database.
        Optional<BookingEntity> boxBooking = bookingRepository.findById(bookingId);

        // Bước 2: Kiểm tra xem cái Hộp đó có rỗng không?
        if (!boxBooking.isPresent()) {
            // Nếu rỗng, quăng lỗi luôn!
            throw new AppException(ErrorCode.BOOKING_NOT_FOUND);
        }

        // Bước 3: Đã chắc chắn hộp có đồ, mình dùng hàm .get() để lấy cái Booking thật ra ngoài
        BookingEntity booking = boxBooking.get();


        // Nếu Booking đã thanh toán hoặc bị hủy thì không cho tạo link nữa, phải xuống db để kiểm tra n
        if (!"PENDING".equals(booking.getStatus())) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION); // Đổi thành mã lỗi "Booking không hợp lệ"
        }

        // 2. Chuẩn bị các tham số gửi sang VNPay
        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", "2.1.0");     // Báo cho VNPay biết mình dùng API phiên bản mấy
        vnp_Params.put("vnp_Command", "pay");       // Lệnh yêu cầu: Thanh toán (pay)
        vnp_Params.put("vnp_TmnCode", vnpTmnCode);  // Mã quầy thu ngân của mình

        //LƯU Ý CỰC QUAN TRỌNG: VNPay quy định số tiền phải nhân thêm 100
        //và đồng thời phải chuyển sang kiểu chuỗi
        long amount = booking.getTotalAmount().longValue() * 100;
        vnp_Params.put("vnp_Amount", String.valueOf(amount));

        vnp_Params.put("vnp_CurrCode", "VND");      // Tiền Việt
        vnp_Params.put("vnp_TxnRef", VNPayConfig.getRandomNumber(8)); // Mã giao dịch ngẫu nhiên
        vnp_Params.put("vnp_OrderInfo", "Thanh toan ve may bay Booking ID: " + bookingId);
        vnp_Params.put("vnp_OrderType", "other");   // Ngành nghề: Khác
        vnp_Params.put("vnp_Locale", "vn");         // Hiển thị tiếng Việt trên màn hình quét mã
        vnp_Params.put("vnp_ReturnUrl", vnpReturnUrl);  // Link đá khách về lại
        vnp_Params.put("vnp_IpAddr", ipAddress);    // IP của khách (VNPay lưu để chống rửa tiền/Gian lận)

        // Sinh ngày giờ tạo và ngày giờ hết hạn (15 phút)
        // khai báo múi giwof việt nam chẩn
        ZoneId zoneId = ZoneId.of("Asia/Ho_Chi_Minh");

        // 2/ khai báo định dạng chuẩn VNpay
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

        // 3/ Sinh ngày giờ tạo
        LocalDateTime now = LocalDateTime.now(zoneId);
        String vnp_CreateDate = now.format(formatter);
        vnp_Params.put("vnp_CreateDate", vnp_CreateDate);

        // 4/ Sinh ngày giờ hết hạn
        LocalDateTime expire = now.plusMinutes(15);
        String vnp_ExpireDate = expire.format(formatter);
        vnp_Params.put("vnp_ExpireDate", vnp_ExpireDate);



        // 3. Sắp xếp các tham số theo thứ tự Alphabet (Bắt buộc theo chuẩn VNPay) theo A-Z
        List<String> fieldNames = new ArrayList<>(vnp_Params.keySet());
        Collections.sort(fieldNames);

        // khai báo 2 cái đường dẫn
        StringBuilder hashData = new StringBuilder();       // chứa chuỗi thô để ném vào máy xay mã hóa
        StringBuilder query = new StringBuilder();          //chứa chuỗi hiển thị lên trình duyệt (phải có dấu & nối nhau)
        Iterator<String> itr = fieldNames.iterator();

        // 4. Lặp qua các tham số để nối thành chuỗi URL và chuỗi mã hóa
        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = vnp_Params.get(fieldName);
            if ((fieldValue != null) && (fieldValue.length() > 0)) {
                try {
                    // Dữ liệu dùng để băm (Hash), thêm vào hashData
                    hashData.append(fieldName);
                    hashData.append('=');
                    //URLEncoder.encode giúp biến các dấu cách (space) thành %20
                    //Biến các ký tự có dấu thành mã an toàn để truyền qua Internet
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));

                    // Dữ liệu dùng để làm URL
                    query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString()));
                    query.append('=');
                    query.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));


                    // Chừng nào chưa phải phần tử cuối cùng thì thêm dấu '&' để nối
                    if (itr.hasNext()) {
                        query.append('&');
                        hashData.append('&');
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // 5. Đóng dấu bảo mật (Tạo chữ ký)
        String queryUrl = query.toString();     //láy chuỗi nãy giwof làm xong

        // ném chuỗi đó và key bí mật vào trả ra chuỗi 128 kí tự lộn xộn
        String vnp_SecureHash = VNPayConfig.hmacSHA512(secretKey, hashData.toString());

        // gắn chuỗi đó vào cuối link URL 
        queryUrl += "&vnp_SecureHash=" + vnp_SecureHash;

        // 6. Nối link gốc với chuỗi URL vừa tạo
        return vnpPayUrl + "?" + queryUrl;
    }

    @Override
    public boolean processVnPayReturn(HttpServletRequest request) {

        // Lấy tất cả params từ url xuống tách ra từng cặp key value
        Map<String, String> vnpParams = extractFromRequest(request);

        // kiểm tra phần chữ kí này có đùng không
        if(!isValidSignature(vnpParams)){
            return false;
        }


        //kiểm tra xem vnpay khách đã trả tiền thành công hay thất bại
        // "00" tức là giao dịch thành công
        if(!"00".equals(vnpParams.get("vnp_ResponseCode"))){
            return false;
        }


        //lấy mã đơn hàng booking id ra khỏi chuỗi thông tin
        String orderInfo = vnpParams.get("vnp_OrderInfo");
        Integer bookingId = extractBookingId(orderInfo);


        //xuống db chuyển trạng thái xác CONFIRMED
        updateBookingStatusToConfirmed(bookingId);


        return true;
    }



    // 1/ lấy các tham số trn trình duyệt xuống
    private Map<String, String> extractFromRequest(HttpServletRequest request){

        Map<String, String> fields = new HashMap<>();

        Enumeration<String> params = request.getParameterNames();


        // duyệt qua tất cả params để lưu vapf Map fileds
        while(params.hasMoreElements()){
            String fieldName = params.nextElement();
            String fieldValue = request.getParameter(fieldName);

            // nếu có cả 2  thì thêm vào
            if (fieldValue != null && !fieldValue.isEmpty()) {
                fields.put(fieldName, fieldValue);
            }
        }

        return fields;

    }



    // 2/ Kiểm tra xem chữ kí này có đúng người dùng không
    private boolean isValidSignature(Map<String, String> fields){
        // lấy cữ kí vnpay gửi
        String vnp_SecureHash = fields.get("vnp_SecureHash");


        //xóa cái chữ kí ra khỏi fields rồi băm lại đống dữ liệu trên coi có ra giôống không
        fields.remove("vnp_SecureHashType");
        fields.remove("vnp_SecureHash");

        // băm lại
        String valueCheck = VNPayConfig.hmacSHA512(secretKey, hashAllFields(fields));

        return valueCheck.equals(vnp_SecureHash);



    }

    // 3/ trichs xuất id cuar vé id đó ra
    private Integer extractBookingId(String orderInfo) {
        // Lúc tạo URL, mình truyền lên: "Thanh toan ve may bay Booking ID: 15"
        // Lệnh replaceAll("[^0-9]", "") là một biểu thức Regex.
        // Nó có nghĩa: "Tìm tất cả những ký tự KHÔNG PHẢI là chữ số (từ 0 đến 9), và xóa sạch chúng đi".
        // Kết quả sẽ tự động lọc ra được số "15".
        String numberOnly = orderInfo.replaceAll("[^0-9]", "");
        return Integer.parseInt(numberOnly);
    }


    private void updateBookingStatusToConfirmed(Integer bookingId) {
//        BookingEntity booking = bookingRepository.findById(bookingId)
//                .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));

        // Bước 1: Đi tìm Booking dưới Database.
        Optional<BookingEntity> boxBooking = bookingRepository.findById(bookingId);

        // Bước 2: Kiểm tra xem cái Hộp đó có rỗng không?
        if (!boxBooking.isPresent()) {
            // Nếu rỗng, quăng lỗi luôn!
            throw new AppException(ErrorCode.BOOKING_NOT_FOUND);
        }

        BookingEntity booking = boxBooking.get();


        if ("PENDING".equals(booking.getStatus())) {
            booking.setStatus("CONFIRMED");
            bookingRepository.save(booking);

            //
            // TODO KAFKA
            //

            // LÚC NÀY PHÁT ĐƯA CHO PRODUCER PHÁT THÔNG BÁO
            paymentKafkaProducer.sendPaymentSuccessEvent(bookingId);
        }
    }





    // Hàm hashAllFields giúp nối chuỗi trên url (Nó dùng để sắp xếp alphabet A-Z)
    private String hashAllFields(Map<String, String> fields) {
        List<String> fieldNames = new ArrayList<>(fields.keySet());
        Collections.sort(fieldNames);
        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = fields.get(fieldName);
            if ((fieldValue != null) && (fieldValue.length() > 0)) {
                sb.append(fieldName);
                sb.append("=");
                try {
                    sb.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (itr.hasNext()) {
                sb.append("&");
            }
        }
        return sb.toString();
    }

















}