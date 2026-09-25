# 03. Lộ trình sửa & học — Luồng Booking

> **Mục đích của tài liệu này**
> Đây KHÔNG phải danh sách bug để sửa cho xong. Đây là lộ trình học: mỗi chặng gắn với
> một khái niệm thực chiến (race condition, distributed lock, idempotency, at-least-once,
> outbox...) mà bạn sẽ bị hỏi khi đi phỏng vấn Java/Spring Boot backend.
>
> **Cách dùng:** làm tuần tự từ Chặng 0 → Chặng 6. Mỗi chặng có 6 phần cố định:
> 1. `HIỆN TRẠNG` — code của bạn đang làm gì (có file:dòng)
> 2. `THỊ TRƯỜNG LÀM SAO` — hệ thống thật (VNA, Vietjet, Amadeus, PSP) làm thế nào và vì sao
> 3. `KIẾN THỨC CẦN NẮM` — từ khóa để tự Google/đọc doc
> 4. `HƯỚNG LÀM` — gợi ý, KHÔNG phải code sẵn. Bạn tự viết.
> 5. `TỰ KIỂM CHỨNG` — cách chứng minh mình đã sửa đúng (quan trọng nhất!)
> 6. `XONG KHI NÀO` — tiêu chí nghiệm thu
>
> **Nguyên tắc xuyên suốt:** *Không sửa cái gì mà bạn chưa tự tay tái hiện được lỗi của nó.*
> Tái hiện được bug → sửa → tái hiện lại thấy hết bug. Đó là vòng lặp của dev thật.

---

## Bảng tổng quan lộ trình

| Chặng | Chủ đề | Mức độ nguy hiểm | Thời gian ước tính | Khái niệm cốt lõi học được |
|-------|--------|------------------|--------------------|-----------------------------|
| 0 | Vá nhanh 5 lỗi hiển nhiên | Thấp nhưng gây rối | 1–2 giờ | Đọc log, debug, exception handling |
| 1 | **Chống bán trùng ghế** | 🔴 Chí mạng | 1–2 ngày | Race condition, DB constraint, source of truth |
| 2 | Vòng đời giữ ghế trên Redis | 🔴 Cao | 1 ngày | Distributed lock, TTL, cache invalidation |
| 3 | **Thanh toán VNPay đúng chuẩn** | 🔴 Chí mạng (mất tiền) | 1–2 ngày | Webhook/IPN, idempotency, đối soát |
| 4 | Kafka tin cậy | 🟠 Trung bình | 1 ngày | At-least-once, DLQ, outbox pattern |
| 5 | Validation nghiệp vụ hành khách | 🟠 Trung bình | 0.5 ngày | Bean Validation, tách tầng validate |
| 6 | Hoàn thiện API + vận hành | 🟡 Thấp | 1 ngày | DTO, phân trang, secret management |

> **Vì sao thứ tự này?** Nguyên tắc trong ngành: sửa theo **mức thiệt hại khi lỗi xảy ra**,
> không sửa theo mức dễ. Bán trùng ghế = 2 khách cùng ngồi 12A ở sân bay (thiệt hại thương hiệu
> + bồi thường). Sai thanh toán = mất tiền thật. Code xấu = chỉ khó chịu. Nên: đúng → an toàn → đẹp.

---

# CHẶNG 0 — Vá nhanh 5 lỗi hiển nhiên

> Mục tiêu: lấy đà, làm quen với việc sửa–chạy–kiểm tra. Không có gì cao siêu ở đây.

### HIỆN TRẠNG

| # | Chỗ | Vấn đề |
|---|-----|--------|
| 0.1 | `PaymentServiceImp.java:88` | `TimeZone.getTimeZone("Etc/GMT+7")` |
| 0.2 | `BookingKafkaConsumerService.java:44-45` | Lấy `customerEmail` xong nhưng gửi tới email hardcode |
| 0.3 | `BookingServiceImp.java:71,74` | `flightRepository.findById(...).get()` |
| 0.4 | `GlobalExceptionHandler.java:29-40` | Bắt `Exception` nhưng không log stacktrace |
| 0.5 | `application.yaml` | Mật khẩu DB, `vnpay.hashSecret`, app-password Gmail nằm trong Git |

### THỊ TRƯỜNG LÀM SAO

**0.1 — Bẫy múi giờ POSIX.** Trong chuẩn POSIX/Olson, `Etc/GMT+7` thực chất là **UTC−7**
(dấu bị đảo ngược so với trực giác). Giờ Việt Nam là UTC+7 nên phải viết `Etc/GMT-7`, hoặc
tốt hơn là `Asia/Ho_Chi_Minh`. Bạn đang lệch **14 tiếng** → `vnp_CreateDate`/`vnp_ExpireDate`
gửi sang VNPay là giờ quá khứ, link thanh toán chết ngay hoặc bị VNPay từ chối.
Hệ thống thật luôn dùng tên vùng địa lý (`Asia/Ho_Chi_Minh`) chứ không dùng offset cứng,
vì vùng địa lý còn mang theo lịch sử đổi giờ (DST). Ngành hàng không cực nhạy với chuyện này:
giờ bay luôn là **giờ địa phương của sân bay**, và mọi timestamp hệ thống lưu bằng UTC.

**0.5 — Secret trong Git.** Trong công ty, commit secret lên Git là sự cố bảo mật phải khai báo.
Secret đã push lên thì coi như đã lộ vĩnh viễn (còn trong git history dù bạn xóa ở commit sau).
Chuẩn: secret nằm ở biến môi trường, hoặc Vault/AWS Secrets Manager/Spring Cloud Config.

### KIẾN THỨC CẦN NẮM
- `java.time.ZoneId` vs `java.util.TimeZone`, vì sao nên bỏ hẳn `Calendar`/`SimpleDateFormat`
  (không thread-safe) để dùng `LocalDateTime` + `DateTimeFormatter`.
- `Optional.orElseThrow()` vs `.get()`.
- Spring Boot property placeholder: `${VNPAY_HASH_SECRET:default}`, file `.env`, `.gitignore`.

### HƯỚNG LÀM
- 0.1: đổi sang `ZoneId.of("Asia/Ho_Chi_Minh")`. Nếu muốn học thêm: viết lại đoạn sinh
  `vnp_CreateDate` bằng `LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))`.
- 0.2: dùng biến `customerEmail` bạn đã lấy sẵn.
- 0.3: `.orElseThrow(() -> new AppException(ErrorCode.FLIGHT_NOT_FOUND))` — bạn đã khai
  `FLIGHT_NOT_FOUND` trong `ErrorCode` mà chưa dùng lần nào.
- 0.4: thêm `log.error("Lỗi không xác định", e)`. Không có stacktrace thì sau này production
  lỗi 500 bạn sẽ mù hoàn toàn.
- 0.5: chuyển 3 secret sang biến môi trường, thêm `.env` vào `.gitignore`, **và đổi mật khẩu
  Gmail app-password + xin lại hashSecret sandbox** (vì đã lộ trong lịch sử Git).
- Dọn rác luôn cho sạch đầu: `import javax.swing.text.html.Option` (`BookingServiceImp.java:20`),
  biến đếm `unlockSeat` không dùng (`:101-113`), hàm `extractBookingId` chết trong consumer,
  `System.out.println` trong `SeatServiceImp` → đổi sang `log.info`.

### TỰ KIỂM CHỨNG
- Gọi `/payments/create-url`, copy giá trị `vnp_CreateDate` trong URL ra, đối chiếu với đồng hồ máy bạn.
- Đặt breakpoint hoặc log `customerEmail` xem có đúng email trong booking không.
- Gọi `/bookings/create` với `runFlightId = 999999` → phải nhận 404 + mã 4004, không phải 500.

### XONG KHI NÀO
Không còn `.get()` trần trên `Optional` trong luồng booking; `git grep hashSecret` không ra
giá trị thật; API trả đúng mã lỗi nghiệp vụ thay vì 500.

---

# CHẶNG 1 — Chống bán trùng ghế 🔴

> Đây là chặng quan trọng nhất của cả đồ án. Nếu chỉ kịp làm 1 chặng, làm chặng này.

### HIỆN TRẠNG

Hệ thống của bạn **không có bất kỳ nơi nào ghi nhận "ghế này đã bán"**. Cụ thể:

1. `SeatEntity.seatStatus` (`SeatEntity.java:30`) tồn tại nhưng **không bao giờ được UPDATE**.
2. `SeatServiceImp.getSeatMap()` (`:43-89`) quyết định trạng thái ghế bằng công thức:
   `trạng thái tĩnh trong DB` + `có key Redis hay không` → `HELD`.
3. Sau khi thanh toán xong, consumer gọi `unlockSeatsByBookingId` → **xóa key Redis**
   (`BookingKafkaConsumerService.java:38`).

Ghép 3 điều trên lại: **ghế vừa bán xong thì key Redis biến mất → seat map hiển thị lại là
trống → người khác lock được và đặt trùng.** Đây không phải rủi ro lý thuyết, nó xảy ra 100%
sau mỗi giao dịch thành công.

Ngoài ra còn 3 đường rò khác:
- `calculateTicket` (`BookingServiceImp.java:296-307`) chỉ tra ghế theo `flightId + seatNumber`
  rồi tính tiền, **không hỏi ghế đã có chủ chưa**.
- Hai hành khách trong **cùng một request** khai cùng `runSeatNumber = "12A"`:
  `isSeatHoldByCurrentUser` trả `true` cho cả hai (vì cùng `userId`) → tạo 2 vé cùng `seat_id`
  (`BookingServiceImp.java:143-176`).
- Ghế random (`SeatRepository.findRandomSeat`, `:29-46`) chỉ loại trừ ghế đã nằm trong
  `passenger_tickets`, **không loại trừ ghế đang HELD trên Redis**, và sau khi chọn xong
  cũng không giữ chỗ. Khách A được auto-gán 12B trong khi khách B đang giữ 12B sắp trả tiền.

### THỊ TRƯỜNG LÀM SAO

**Khái niệm "source of truth" (nguồn chân lý).** Trong mọi hệ thống bán hàng tồn kho có hạn
(vé máy bay, vé xem phim, sàn TMĐT), quy tắc bất di bất dịch là:

> **Redis là hàng rào UX. Database là hàng rào cuối cùng (last line of defense).**

Redis lock chỉ để 2 khách đang xem sơ đồ ghế không cùng chọn 1 ghế → trải nghiệm mượt.
Nhưng Redis có TTL, có thể mất dữ liệu khi restart, có thể bị network partition. Nên **không
được để Redis là nơi duy nhất biết ghế đã bán**. Trạng thái "đã bán" phải nằm trong DB, và
được bảo vệ bằng **UNIQUE constraint** — thứ duy nhất mà race condition không vượt qua nổi.

Chú ý: chính file `docs/database-design.md` của bạn (mục 1.12) đã ghi:
> `booking_seats.flight_seat_id` | FK → flight_seats, **UQ**, NN | *Ghế cụ thể trên chuyến bay
> (1 ghế chỉ thuộc 1 booking)*

Bạn đã thiết kế đúng trên giấy nhưng chưa hiện thực hóa constraint đó xuống DB thật.

**Ngành hàng không phân biệt 2 tầng tồn kho** — điều sinh viên hay nhầm:
1. **Inventory / seat availability**: còn bao nhiêu chỗ bán được theo hạng vé (booking class).
   Đây là thứ hãng cho phép **overbooking** có chủ ý (bán 105% ghế vì thống kê ~5% khách no-show).
2. **Seat assignment**: gán ghế vật lý cụ thể (12A). Cái này **tuyệt đối không được trùng**.
   Với LCC như Vietjet, chọn ghế là dịch vụ phụ trợ (*ancillary revenue*) có thu phí — đúng như
   `price_multiplier` bạn đang làm.

Đồ án của bạn đang gộp 2 tầng này làm một, và điều đó chấp nhận được ở quy mô đồ án — nhưng
bạn nên **nói được sự khác biệt này khi bảo vệ/phỏng vấn**, đó là điểm cộng lớn.

**Ghế "auto-assign" trong thực tế:** khi khách không chọn ghế, hãng không gán ghế lúc đặt vé.
Ghế được gán ở bước **check-in** (24h trước giờ bay). Lý do: gán sớm thì hãng mất khả năng
tối ưu (cân bằng trọng tải máy bay, dồn ghế trống, xếp gia đình ngồi cạnh nhau).
Bạn đang gán ngay lúc booking — đơn giản hơn, chấp nhận được, nhưng phải xử lý cho đúng khóa.

### KIẾN THỨC CẦN NẮM
- **Race condition** và tại sao `SELECT rồi INSERT` (check-then-act) luôn sai trong môi trường
  đa luồng. Từ khóa: *TOCTOU — time of check to time of use*.
- **Unique constraint** ở tầng DB, bắt `DataIntegrityViolationException` trong Spring.
- **Optimistic locking** (`@Version` — bạn đã khai ở `SeatEntity` mà chưa dùng) vs
  **Pessimistic locking** (`SELECT ... FOR UPDATE`, `@Lock(LockModeType.PESSIMISTIC_WRITE)`).
- **Isolation level** của MySQL InnoDB (mặc định `REPEATABLE READ`) và vì sao nó vẫn không cứu
  được bạn khỏi bug "2 transaction cùng INSERT" nếu không có UNIQUE.
- **Cache invalidation** — "một trong hai vấn đề khó nhất khoa học máy tính".

### HƯỚNG LÀM

Bạn có 2 phương án. Hãy **tự chọn và tự bảo vệ được lựa chọn** — đây chính là câu hỏi phỏng vấn.

**Phương án A (khuyên dùng cho đồ án — đơn giản, đúng, dễ giải thích):**
Coi bảng `passenger_tickets` là nơi ghi nhận ghế đã bán.
- Thêm UNIQUE constraint ở DB: `UNIQUE(seat_id)` — vì mỗi `seat` của bạn đã gắn với 1
  `flight_id` rồi, nên `seat_id` là duy nhất trong phạm vi chuyến bay.
  ⚠️ Cân nhắc: booking bị CANCELLED thì ghế phải bán lại được → UNIQUE cứng sẽ chặn luôn.
  Cách xử lý: khi hủy booking thì **xóa** dòng `passenger_tickets` (hoặc dùng partial index —
  MySQL không hỗ trợ, nên phương án xóa là thực tế nhất). Hãy tự nghĩ xem bạn muốn giữ lịch sử
  vé hủy không, rồi quyết định. Đây là *trade-off* thật, không có đáp án duy nhất.
- Trong `calculateTicket`, trước khi tạo vé: query kiểm tra ghế đã có vé thuộc booking
  `PENDING`/`CONFIRMED` chưa → nếu có thì ném `SEAT_ALREADY_LOCKED`.
- **Nhưng vẫn phải có UNIQUE.** Bước query trên chỉ để trả lỗi đẹp cho user; UNIQUE mới là
  thứ chặn được 2 request chạy song song đúng cùng một micro-giây. Bắt
  `DataIntegrityViolationException` → chuyển thành `AppException(SEAT_ALREADY_LOCKED)`.

**Phương án B (sát thiết kế DB gốc của bạn, đúng chuẩn ngành hơn, khó hơn):**
Dùng bảng `flight_seats` với cột `status (AVAILABLE | BOOKED | BLOCKED)` như `database-design.md`
mục 1.7 đã thiết kế, cập nhật trạng thái bằng **conditional update**:
```sql
UPDATE flight_seats SET status='BOOKED'
WHERE id = :id AND status='AVAILABLE'
```
Nếu số dòng bị ảnh hưởng = 0 → ghế đã bị người khác lấy → ném lỗi.
Đây là kỹ thuật *compare-and-set ở tầng DB*, rất hay, đáng học.

**Ba việc bắt buộc làm ở cả hai phương án:**
1. **Chặn trùng ghế trong cùng một request.** Trước khi xử lý, gom toàn bộ `runSeatNumber` vào
   một `Set` và so sánh `set.size()` với số ghế; tương tự cho `returnSeatNumber`. Đây là
   validate rẻ tiền nhất, làm ở đầu `createBooking`.
2. **Sửa `findRandomSeat`.** Phải loại trừ cả ghế đang HELD trên Redis. Cách làm: scan key
   Redis lấy danh sách seatNumber đang bị giữ → truyền vào query dưới dạng `NOT IN (:heldSeats)`.
   ⚠️ Bẫy: `NOT IN ()` rỗng sẽ làm SQL lỗi cú pháp — nhớ xử lý trường hợp danh sách rỗng.
   Và sau khi random được ghế, **phải lock nó trên Redis luôn** rồi mới tính tiền, nếu không
   bạn lại rơi vào chính bug cũ.
3. **Xóa cache `static_seatmap` khi ghế đổi trạng thái.** Consumer của bạn đã có sẵn dòng
   comment `// THIẾU XÓA STATIC_MAP TRÊN REDIS` (`BookingKafkaConsumerService.java:40`) — bạn
   đã tự nhận ra rồi, giờ làm nốt. Đồng thời **cân nhắc bỏ luôn `status` khỏi cache**: cache
   nên chỉ chứa dữ liệu *tĩnh* (số ghế, hạng ghế, giá), còn `status` tính tại thời điểm gọi từ
   DB + Redis. Nguyên tắc: **không bao giờ cache dữ liệu biến động nhanh chung với dữ liệu tĩnh.**

### TỰ KIỂM CHỨNG — phần quan trọng nhất chặng này

Bạn phải **tự tay tái hiện bug double-booking** trước khi sửa. Hai cách:

**Cách 1 — Bằng tay (nhanh, thấy ngay lỗi ở mục HIỆN TRẠNG):**
```
1. Lock ghế 12A, tạo booking, thanh toán sandbox thành công.
2. Chờ consumer chạy xong (xem log "Giải phóng ghế 12A thành công").
3. redis-cli KEYS "booking:flight:1:seat:*"   → key 12A đã biến mất
4. GET /seat/flight/1                          → 12A hiện lại là trống  ❌
5. Lock 12A lần nữa → thành công → đặt vé lần 2 → BÁN TRÙNG.
```

**Cách 2 — Test race condition thật (đáng giá nhất để học):**
Viết một test dùng `ExecutorService` + `CountDownLatch` cho 10 thread cùng gọi `createBooking`
với **cùng một ghế** tại đúng một thời điểm:
```java
var latch = new CountDownLatch(1);
var pool  = Executors.newFixedThreadPool(10);
for (int i = 0; i < 10; i++) {
    pool.submit(() -> { latch.await(); bookingService.createBooking(sameRequest, userId); });
}
latch.countDown();          // thả cả 10 thread ra cùng lúc
// Sau khi chạy: đếm số dòng passenger_tickets có seat_id đó → PHẢI = 1
```
Chạy test này **trước khi sửa** (sẽ thấy >1 dòng) và **sau khi sửa** (phải = 1, 9 request kia
nhận lỗi rõ ràng chứ không phải 500). Nếu bạn làm được bài này và kể lại trong phỏng vấn,
đó là điểm cộng rất lớn — hầu hết sinh viên chưa từng viết test kiểu này.

### XONG KHI NÀO
- DB có UNIQUE chặn 2 vé cùng ghế (thử `INSERT` tay bằng SQL để xác nhận constraint hoạt động).
- Test 10 thread song song chỉ 1 thread thành công.
- Sau khi thanh toán thành công, `GET /seat/flight/{id}` hiển thị ghế đó là **đã bán**, không
  phải trống, và không lock lại được.
- Booking bị hủy → ghế quay lại bán được.

---

# CHẶNG 2 — Vòng đời giữ ghế trên Redis 🔴

### HIỆN TRẠNG

Phần lock/unlock lõi bạn **làm đúng** — cần ghi nhận:
`setIfAbsent` (chính là `SET NX EX`) và unlock bằng **Lua script so sánh owner rồi mới DEL**
(`RedisServiceImp.java:57-80`) là đúng sách giáo khoa distributed lock. Nhiều người đi làm vài
năm vẫn viết `if (get(key).equals(me)) del(key)` — sai vì không atomic. Bạn tránh được rồi.

Vấn đề nằm ở **vòng đời xung quanh** cái lock đó:

| # | Chỗ | Vấn đề |
|---|-----|--------|
| 2.1 | `BookingServiceImp.java:201-216` | `extendSeatLock` gọi với `seatNumber = null` khi khách không chọn ghế → thao tác lên key `...:seat:null` |
| 2.2 | `BookingServiceImp.java:100` | `expire()` trả về `boolean` (key còn sống không) nhưng bạn bỏ qua kết quả — key có thể vừa hết hạn giữa lúc validate và lúc gia hạn |
| 2.3 | `BookingServiceImp.java:61-89` | `@Transactional` không quản lý được Redis: nếu DB rollback ở giữa (VD `BAGGAGE_NOT_FOUND`), ghế vẫn bị giữ thêm 15 phút |
| 2.4 | `BookingCleanupJob.java` | Hủy booking quá hạn nhưng không nhả key Redis |
| 2.5 | `SeatController.java:28-45` | `lockSeat` không kiểm tra ghế có tồn tại thuộc chuyến bay không, và không giới hạn số ghế 1 user được giữ |
| 2.6 | Rải rác | Con số 15 phút nằm ở 4 nơi khác nhau: `RedisServiceImp.HOLD_TIME`, `BookingServiceImp.TIME_TO_EXTEND_SEAT_LOCK`, cron job, `vnp_ExpireDate` |
| 2.7 | `SeatServiceImp.java:127-145` | Mọi exception (kể cả lỗi DB) đều bị bọc thành `REDIS_OPERATION_FAILED`; Redis chết là API sập hẳn (đoạn fallback DB đang bị comment) |

### THỊ TRƯỜNG LÀM SAO

**"Ticketing Time Limit" (TTL) là khái niệm có thật trong ngành hàng không**, không phải bạn
tự nghĩ ra. Khi giữ chỗ mà chưa xuất vé, hệ thống đặt một *deadline*: đặt online thường
15–30 phút; đặt qua đại lý có thể 24–72h. Quá hạn → PNR tự động hủy, ghế trả về kho.
Bạn đang làm đúng mô hình này. Điểm khác biệt: hệ thống thật lưu deadline **xuống DB**
(`bookings.payment_deadline` — chính `database-design.md` mục 1.9 của bạn đã có cột này mà
`BookingEntity` chưa khai!), không phụ thuộc vào TTL của Redis.

**Vì sao phải lưu deadline xuống DB?** Vì Redis có thể mất dữ liệu (restart, evict khi đầy
memory, failover). Nếu deadline chỉ nằm ở TTL Redis, Redis restart = toàn bộ booking đang chờ
thanh toán mất deadline → hoặc bị hủy oan hoặc treo vĩnh viễn. **Redis giữ chỗ tạm; DB giữ
sự thật.** Đây lại chính là bài học Chặng 1, lặp lại ở khía cạnh thời gian.

**Fail-open vs fail-closed (mục 2.7).** Khi một dependency chết, bạn phải quyết định:
- *Fail-open*: vẫn cho đi tiếp với chức năng suy giảm. Ví dụ Redis chết → seat map vẫn đọc từ
  DB, chỉ là không hiển thị được trạng thái HELD.
- *Fail-closed*: chặn luôn. Đúng cho thao tác **ghi tiền/tồn kho** — Redis chết thì không cho
  đặt vé còn hơn bán trùng.

Hệ thống thật: **đọc thì fail-open, ghi thì fail-closed**. Code bạn hiện đang fail-closed cho
cả việc *xem sơ đồ ghế* — quá khắt khe, Redis hắt hơi là cả web sập.

**Giới hạn số ghế giữ (mục 2.5)** là chống lạm dụng cơ bản. Không có nó, một script có thể
lock sạch 180 ghế của chuyến bay trong 2 giây và chặn mọi khách thật trong 15 phút — dạng
tấn công *inventory denial*, có thật trong ngành TMĐT và bán vé.

### KIẾN THỨC CẦN NẮM
- Redis: `SET NX EX`, `EXPIRE`, `TTL`, `EVAL` (Lua), vì sao Lua đảm bảo atomic (Redis đơn luồng).
- `SCAN` vs `KEYS` — bạn đã dùng `SCAN` (`RedisScanServiceImp`), tốt. Tự trả lời được câu:
  *"vì sao `KEYS *` bị cấm ở production?"*
- Spring: `TransactionSynchronizationManager.registerSynchronization()` /
  `@TransactionalEventListener(phase = AFTER_ROLLBACK)` — cách chạy code dọn dẹp Redis đúng
  thời điểm transaction DB kết thúc.
- `@ConfigurationProperties` để gom hằng số cấu hình về một chỗ.
- Khái niệm: *lease*, *fencing token*, và tại sao Redlock gây tranh cãi (đọc bài phản biện của
  Martin Kleppmann — rất đáng đọc, sẽ giúp bạn nói chuyện có chiều sâu về distributed lock).

### HƯỚNG LÀM
- 2.1 + 2.2: chỉ gia hạn khi `seatNumber` không rỗng; kiểm tra giá trị trả về của `expire`,
  nếu `false` (key đã chết) thì ném `SEAT_HOLD_EXPIRED_OR_INVALID` thay vì đi tiếp.
- 2.3: bọc phần Redis trong cơ chế chạy sau khi transaction rollback. Cách đơn giản nhất cho
  đồ án: `try { ... } catch (Exception e) { rollbackAllSeatsInRequest(...); throw e; }`.
  Cách "đúng bài" hơn: dùng `TransactionSynchronization.afterCompletion(STATUS_ROLLED_BACK)`.
  Hãy thử cả hai để hiểu vì sao cách 2 tốt hơn (nó bắt được cả lỗi xảy ra lúc commit).
- 2.4: thêm cột `payment_deadline` vào `BookingEntity` (DB design của bạn đã có), cron dựa vào
  cột đó thay vì `created_at`, và khi hủy thì nhả luôn key Redis.
- 2.5: kiểm tra ghế tồn tại + đếm số key `booking:flight:{id}:seat:*` mà user đang giữ,
  giới hạn ví dụ 9 ghế (đúng bằng giới hạn số khách/booking phổ biến của các hãng).
- 2.6: gom về một `@ConfigurationProperties` class, ví dụ `booking.hold-minutes: 15`.
  **Lưu ý logic:** hiện lock ban đầu 15 phút, tới lúc `createBooking` lại gia hạn thêm 15 phút,
  trong khi cron tính hạn từ `created_at` + 15 phút. Ba mốc thời gian này đang "gần trùng nhau
  một cách may mắn". Hãy vẽ ra timeline trên giấy và tự trả lời: *ghế được giữ tổng cộng bao lâu
  tính từ lúc khách bấm chọn ghế?* Nếu bạn không trả lời ngay được thì đó chính là dấu hiệu
  logic đang rối và cần gom lại.
- 2.7: tách `catch` riêng cho lỗi Redis và lỗi DB; khi Redis lỗi ở luồng **đọc** seat map thì
  log cảnh báo + trả dữ liệu DB (bỏ phần HELD) thay vì ném 500.

### TỰ KIỂM CHỨNG
```bash
redis-cli TTL booking:flight:1:seat:12A     # xem TTL còn lại, gọi trước/sau khi tạo booking
redis-cli KEYS "booking:flight:1:seat:*"    # đếm số ghế 1 user đang giữ
redis-cli DEL booking:flight:1:seat:12A     # giả lập ghế hết hạn giữa chừng → API phải báo lỗi rõ ràng
```
- Tắt hẳn Redis (`docker stop redis`) → `GET /seat/flight/1` **vẫn phải trả về danh sách ghế**,
  còn `POST /seat/lock` thì được phép báo lỗi. Đó là fail-open đọc / fail-closed ghi.
- Gửi request tạo booking với `runBaggageId` không tồn tại → DB rollback → kiểm tra
  `redis-cli KEYS` xem ghế đã được nhả chưa (trước khi sửa: vẫn còn; sau khi sửa: đã sạch).

### XONG KHI NÀO
Không còn key `...:seat:null`; ghế luôn được nhả khi booking thất bại hoặc bị hủy; tắt Redis
không làm sập API đọc; mọi mốc 15 phút chỉ khai báo ở đúng một chỗ.

---

# CHẶNG 3 — Thanh toán VNPay đúng chuẩn 🔴

> Chặng này liên quan tiền thật. Trong công ty, code thanh toán luôn được review kỹ nhất.

### HIỆN TRẠNG

| # | Chỗ | Vấn đề |
|---|-----|--------|
| 3.1 | `PaymentController.java:33-42` | Chỉ có `vnpay-return` (redirect trình duyệt), **không có IPN** |
| 3.2 | `PaymentServiceImp.java:228-235` | Lấy `bookingId` bằng cách xóa mọi ký tự không phải số trong `vnp_OrderInfo` |
| 3.3 | `PaymentServiceImp.java:80` | `vnp_TxnRef` random và **không lưu xuống đâu cả** |
| 3.4 | `PaymentServiceImp.java:151-179` | Không đối chiếu `vnp_Amount` với `totalAmount` của booking |
| 3.5 | `PaymentServiceImp.java:254-264` | Nếu booking đã bị cron hủy trước khi tiền về → im lặng bỏ qua, API vẫn trả "thành công" |
| 3.6 | Toàn bộ | Bảng `payments` trong `database-design.md` mục 1.14 chưa được dùng |
| 3.7 | `PaymentServiceImp.java:76` | `totalAmount.longValue() * 100` — cắt phần thập phân trước khi nhân |

### THỊ TRƯỜNG LÀM SAO

**3.1 — Return URL vs IPN: hiểu nhầm kinh điển của người mới làm thanh toán.**

| | Return URL | IPN (Instant Payment Notification) |
|---|---|---|
| Ai gọi | **Trình duyệt của khách** | **Server VNPay gọi thẳng server bạn** |
| Mục đích | Hiển thị "Thanh toán thành công" cho khách xem | Ghi nhận kết quả giao dịch |
| Có đáng tin không | ❌ Khách đóng tab, mất mạng, tắt máy → không bao giờ tới | ✅ VNPay **retry nhiều lần** cho tới khi server bạn trả về `RspCode=00` |
| Được phép cập nhật DB không | ❌ Tuyệt đối không dùng làm nguồn chân lý | ✅ Đây mới là nơi CONFIRMED booking |

Kịch bản hỏng của bạn ngay lúc này: khách quét QR, ngân hàng trừ tiền, khách tắt trình duyệt vì
tưởng xong → `vnpay-return` không bao giờ được gọi → booking mãi PENDING → cron hủy sau 15 phút
→ **khách mất tiền và không có vé**. Đây là loại sự cố mà công ty phải hoàn tiền thủ công và
xin lỗi khách. Mọi cổng thanh toán (VNPay, MoMo, Stripe, PayPal) đều có cơ chế webhook/IPN vì
lý do này, và tài liệu của họ đều ghi rõ *"không dùng return URL để cập nhật đơn hàng"*.

**3.2 + 3.3 — `vnp_TxnRef` là mã đối soát.** Trong ngành thanh toán, `TxnRef` (merchant
transaction reference) là **khóa liên kết duy nhất giữa đơn hàng của bạn và giao dịch của cổng**.
Cuối ngày/cuối tháng, kế toán chạy **đối soát (reconciliation)**: tải file giao dịch từ VNPay,
so từng dòng với DB của mình theo `TxnRef` để phát hiện lệch (tiền về mà đơn chưa confirm,
hoặc ngược lại). Bạn đang sinh `TxnRef` ngẫu nhiên rồi **vứt đi** → không có khả năng đối soát,
không tra cứu được giao dịch nào ứng với booking nào, không hoàn tiền được, không khiếu nại được.

Còn cách lấy `bookingId` bằng regex `replaceAll("[^0-9]", "")` từ `vnp_OrderInfo`: chỉ cần một
ngày nào đó bạn đổi text thành `"Thanh toan ve may bay VN123 - Booking ID: 15"` là ra `12315`.
Code phụ thuộc vào định dạng chuỗi mô tả cho người đọc là *cực kỳ dễ vỡ*.

**3.4 + 3.5 — Nguyên tắc "không bao giờ tin số tiền do bên ngoài gửi về".** Chữ ký HMAC bảo vệ
tính toàn vẹn, đúng — nhưng vẫn phải so `vnp_Amount` với số tiền đơn hàng trong DB, vì:
- Đơn có thể bị sửa giá sau khi tạo link thanh toán (khách mở link cũ);
- Bug logic phía bạn có thể tạo link sai số tiền;
- Đây là chốt kiểm tra độc lập, không tốn gì mà cứu được rất nhiều.

Với 3.5 (tiền về sau khi đơn đã hủy): hệ thống thật **không im lặng**. Nó ghi nhận giao dịch
vào bảng `payments` với trạng thái riêng, bắn cảnh báo cho đội vận hành, và đưa vào hàng đợi
**hoàn tiền (refund)**. Im lặng nuốt tiền của khách là lỗi nghiệp vụ nghiêm trọng nhất trong
mảng payment.

**3.7 — Tiền và kiểu số thực.** Quy tắc ngành: **không bao giờ dùng `float`/`double` cho tiền**;
dùng `BigDecimal` (bạn đang làm đúng) hoặc số nguyên đơn vị nhỏ nhất (cents/đồng). Nhưng
`.longValue() * 100` là bạn cắt phần lẻ *trước* khi nhân — đúng thứ tự phải là
`.multiply(BigDecimal.valueOf(100)).longValue()`. VND thường không có xu nên hôm nay chưa lộ,
nhưng thói quen này sẽ gây sự cố khi làm hệ thống đa tiền tệ (USD có 2 chữ số thập phân).

**Idempotency (tính lũy đẳng)** — từ khóa quan trọng nhất chặng này. IPN **sẽ được VNPay gọi
nhiều lần** cho cùng một giao dịch (do retry, do timeout mạng). Endpoint của bạn phải đảm bảo:
gọi 1 lần hay 10 lần thì kết quả trong DB **giống hệt nhau** — trừ tiền 1 lần, gửi mail 1 lần,
xuất vé 1 lần. Đây là câu hỏi phỏng vấn gần như chắc chắn sẽ gặp khi bạn kể mình làm payment.

### KIẾN THỨC CẦN NẮM
- Webhook/IPN, retry, idempotency key, reconciliation (đối soát).
- HMAC-SHA512 và vì sao phải verify chữ ký **trước** khi đọc bất kỳ trường nào khác.
- State machine của đơn hàng: `PENDING → CONFIRMED | CANCELLED | EXPIRED`, và các chuyển đổi
  **không hợp lệ** phải bị chặn (VD `CANCELLED → CONFIRMED`).
- `@Transactional` + `optimistic lock` khi cập nhật trạng thái đơn từ 2 nguồn song song
  (IPN và cron job có thể cùng chạm vào 1 booking đúng lúc — bạn đã khai `@Version` ở
  `BookingEntity`, giờ là lúc nó có tác dụng thật).

### HƯỚNG LÀM
1. **Đặt `vnp_TxnRef` = mã tham chiếu của bạn** (VD `bookingId` + timestamp, hoặc UUID) và
   **lưu nó vào bảng `payments`** ngay khi tạo link, trạng thái `PENDING`.
2. **Viết endpoint `/payments/vnpay-ipn`** (VNPay gọi bằng `GET`). Quy trình chuẩn trong endpoint:
   ```
   verify chữ ký          → sai   : trả RspCode 97
   tra payment theo TxnRef → không thấy: trả RspCode 01
   so sánh vnp_Amount     → lệch : trả RspCode 04
   nếu payment đã xử lý rồi → trả RspCode 02  (đây là bước IDEMPOTENCY)
   nếu ResponseCode == 00 → cập nhật payment SUCCESS + booking CONFIRMED + bắn Kafka
   trả về RspCode 00      (nếu không trả 00, VNPay sẽ gọi lại)
   ```
   Chú ý: **VNPay quyết định retry hay không dựa vào response body của bạn**, không phải HTTP
   status. Đọc kỹ tài liệu VNPay phần IPN để trả đúng format JSON `{"RspCode":"00","Message":"Confirm Success"}`.
3. **Đổi vai trò `vnpay-return`** thành chỉ đọc trạng thái booking trong DB rồi hiển thị/redirect
   về frontend. Không cập nhật gì cả.
4. **Xử lý ca "tiền về sau khi đơn đã hủy"**: ghi `payments` với trạng thái phản ánh đúng sự
   thật, `log.error` để cảnh báo, và trong đồ án thì ít nhất phải **không trả về "thành công"**.
   Ghi vào README rằng luồng refund thuộc phạm vi mở rộng — thầy/người phỏng vấn sẽ đánh giá
   cao việc bạn *nhận biết* được vấn đề dù chưa làm.
5. Sửa 3.7 (`multiply` trước, `longValue` sau).

> **Bẫy khi test IPN ở localhost:** VNPay không gọi được vào `localhost`. Dùng `ngrok`
> (`ngrok http 8080`) để có URL public, khai báo URL đó ở portal sandbox VNPay. Đây cũng là
> cách mọi người test webhook Stripe/MoMo trong thực tế — kỹ năng rất thực dụng.

### TỰ KIỂM CHỨNG
- Thanh toán sandbox thành công rồi **đóng tab ngay** khi vừa thấy màn hình ngân hàng chuyển
  hướng → booking vẫn phải thành `CONFIRMED` nhờ IPN.
- Gọi lại endpoint IPN **3 lần với đúng cùng một bộ tham số** → booking vẫn `CONFIRMED`,
  bảng `payments` vẫn 1 dòng SUCCESS, email chỉ gửi 1 lần, Kafka chỉ bắn 1 event.
  *(Đây chính là bài kiểm tra idempotency — làm được là bạn hiểu khái niệm này thật.)*
- Sửa tay 1 ký tự trong `vnp_SecureHash` rồi gọi IPN → phải bị từ chối.
- Sửa `vnp_Amount` trong request giả → phải bị từ chối (dù bạn tự ký lại chữ ký cho khớp,
  vì có bước so sánh với DB).

### XONG KHI NÀO
Booking chỉ được `CONFIRMED` từ IPN; bảng `payments` có đủ dấu vết mọi giao dịch; gọi IPN
lặp lại không gây tác dụng phụ; mọi trường hợp bất thường đều có log rõ ràng.

---

# CHẶNG 4 — Kafka tin cậy 🟠

### HIỆN TRẠNG

| # | Chỗ | Vấn đề |
|---|-----|--------|
| 4.1 | `BookingKafkaConsumerService.java:50-52` | `catch (Exception e) { log.error(...) }` → nuốt lỗi, offset vẫn commit, **event mất vĩnh viễn** |
| 4.2 | Toàn bộ consumer | Chưa idempotent: Kafka giao hàng *at-least-once*, message có thể đến 2 lần |
| 4.3 | `PaymentServiceImp.java:255-263` | `bookingRepository.save()` rồi `kafkaTemplate.send()` trong cùng transaction → nếu DB commit xong mà Kafka chết thì event mất; nếu Kafka gửi xong mà DB rollback thì event "ma" |

### THỊ TRƯỜNG LÀM SAO

**4.1 — "Nuốt exception" là phản mẫu (anti-pattern) nguy hiểm nhất khi làm consumer.**
Kafka commit offset sau khi hàm `@KafkaListener` return bình thường. Bạn `catch` mọi thứ rồi
`return` → Kafka tưởng bạn xử lý thành công → tiến offset → **message không bao giờ được xử
lý lại**. Hậu quả trong nghiệp vụ của bạn: khách đã trả tiền nhưng ghế không được nhả, email
không được gửi, và **không có cách nào biết để chạy lại** ngoài việc mò đọc log.

Chuẩn ngành: cho exception ném ra để Spring Kafka `DefaultErrorHandler` retry theo backoff,
và sau N lần thất bại thì đẩy message sang **DLQ (Dead Letter Queue)** — một topic riêng chứa
message hỏng để đội vận hành xem lại và replay. Không có DLQ = dữ liệu hỏng biến mất im lặng.

**4.2 — At-least-once.** Kafka đảm bảo *ít nhất một lần*, không phải *đúng một lần*
(exactly-once có tồn tại nhưng phức tạp và ràng buộc nhiều). Nghĩa là consumer **bắt buộc**
phải chịu được việc xử lý lại cùng một message. Với luồng của bạn: nếu event
`payment-completed` đến 2 lần thì khách nhận 2 email xác nhận — khó chịu nhưng chưa chết;
tuy nhiên nếu sau này bạn thêm bước "cộng điểm thưởng" hay "trừ tồn kho" thì đó là bug tiền bạc.
Cách xử lý phổ biến: kiểm tra trạng thái trước khi hành động (*"booking này đã gửi mail chưa?"*),
hoặc lưu bảng `processed_events(event_id)` với UNIQUE.

**4.3 — Dual write problem & Transactional Outbox Pattern.** Đây là một trong những pattern
kinh điển của microservice, rất đáng để bạn nêu trong đồ án:

> Vấn đề: bạn cần ghi DB **và** gửi message. Hai hệ thống khác nhau, không có transaction chung.
> Ghi DB xong mà gửi Kafka lỗi → mất event. Gửi Kafka xong mà DB rollback → event nói dối.

Giải pháp Outbox: trong **cùng một transaction DB**, ghi cả dữ liệu nghiệp vụ và một dòng vào
bảng `outbox_events`. Một tiến trình riêng (poller hoặc CDC như Debezium) đọc bảng outbox và
publish sang Kafka. Vì cả hai cùng nằm trong một transaction DB, không bao giờ lệch nhau.

Với đồ án, bạn **không nhất thiết phải implement Outbox** — nhưng *biết vấn đề và gọi đúng tên
giải pháp* đã là điểm cộng lớn. Một bước trung gian rẻ tiền: dùng
`@TransactionalEventListener(phase = AFTER_COMMIT)` để chỉ bắn Kafka **sau khi DB commit thành
công** — giải quyết được nửa vấn đề (không còn event ma), và bạn nói được vì sao nó vẫn chưa
giải quyết nửa còn lại (DB commit xong, app chết trước khi kịp gửi Kafka).

### KIẾN THỨC CẦN NẮM
- Kafka: offset, consumer group, `enable.auto.commit`, `AckMode`, rebalance.
- Spring Kafka: `DefaultErrorHandler`, `FixedBackOff`/`ExponentialBackOff`,
  `DeadLetterPublishingRecoverer`, `@RetryableTopic`.
- Delivery semantics: at-most-once / at-least-once / exactly-once.
- Transactional Outbox, CDC, `@TransactionalEventListener`.

### HƯỚNG LÀM
- 4.1: bỏ `catch` bao trùm; cấu hình `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
  để tự động đẩy sang topic `booking-payment-completed-topic.DLT`. Chỉ `catch` những lỗi mà
  bạn **cố ý bỏ qua** (VD message sai định dạng thì retry vô ích → cho vào DLQ ngay,
  dùng `ExceptionClassifier` để phân biệt lỗi *retry được* và *không retry được*).
- 4.2: trước khi gửi email/nhả ghế, kiểm tra trạng thái để thao tác trở nên lũy đẳng.
  Gợi ý: thêm cột đánh dấu đã xử lý, hoặc kiểm tra booking đã `CONFIRMED` và ghế đã nhả chưa.
- 4.3: thử `@TransactionalEventListener(phase = AFTER_COMMIT)` cho bước bắn Kafka. Sau đó ghi
  vào tài liệu đồ án phần "hạn chế còn lại + hướng mở rộng Outbox".

### TỰ KIỂM CHỨNG
```bash
# Tắt consumer, tạo 1 giao dịch thành công, bật consumer lại → event phải được xử lý (không mất)
# Cố tình ném exception trong consumer → xem log retry, rồi kiểm tra message có sang DLT không:
kafka-console-consumer --bootstrap-server localhost:9094 \
  --topic booking-payment-completed-topic.DLT --from-beginning
# Gửi tay 1 message trùng vào topic → email chỉ được gửi 1 lần
kafka-console-producer --bootstrap-server localhost:9094 --topic booking-payment-completed-topic
```
- Tắt Kafka rồi thực hiện thanh toán → quan sát xem booking có `CONFIRMED` mà event mất không.
  Đó chính là dual-write problem bạn vừa đọc, tự tay tái hiện.

### XONG KHI NÀO
Message lỗi không biến mất im lặng (có DLQ); xử lý message trùng không gây tác dụng phụ;
bạn giải thích được bằng lời tại sao Outbox tồn tại.

---

# CHẶNG 5 — Validation nghiệp vụ hành khách 🟠

### HIỆN TRẠNG
- `createBooking` **không kiểm tra quy tắc hành khách nào**. Trớ trêu là bạn đã viết đúng luật
  này ở `FlightServiceImp.java:36-42` (search) nhưng lúc đặt vé thật thì bỏ qua — khách hoàn toàn
  có thể search 1 người rồi POST thẳng booking 20 em bé không người lớn.
- `ErrorCode.INVALID_PASSENGER_RULE` và `ONE_ADULT_ONLY_ONE_INFANT` khai rồi nhưng không dùng ở booking.
- **INFANT bị "mồ côi"**: được `save` vào bảng `passengers` rồi `continue` trước khi tạo
  `passenger_ticket` (`BookingServiceImp.java:243-245`) → không có gì nối em bé với booking.
  Query ra booking sẽ không thấy em bé đâu cả.
- Không kiểm tra `dateOfBirth` có khớp `passengerType` không (khai INFANT nhưng sinh năm 1990).
- Không kiểm tra `flight.status`, không kiểm tra chuyến bay đã khởi hành chưa, không kiểm tra
  chiều về có sau chiều đi không, không chặn `runFlightId == returnFlightId`.
- Nếu `returnFlightId` null nhưng khách vẫn gửi `returnSeatNumber` → bị bỏ qua âm thầm.

### THỊ TRƯỜNG LÀM SAO

**Quy tắc hành khách là luật hàng không, không phải quy ước tùy hứng:**
- **INFANT** (< 2 tuổi tại **ngày bay**, không phải ngày đặt): ngồi lòng người lớn, **không có
  ghế riêng**, giá thường ~10% giá người lớn. Mỗi người lớn kèm tối đa 1 em bé — vì số mặt nạ
  dưỡng khí phụ trên mỗi hàng ghế có giới hạn. Đây là quy định an toàn bay thật.
- **CHILD** (2–11 tuổi): có ghế riêng, giá thường 75–90% người lớn.
- **ADULT** (≥ 12 tuổi).
- Mỗi booking thường tối đa **9 khách** (giới hạn của hệ thống GDS; đông hơn phải đặt theo
  đoàn qua kênh khác).
- Tuổi được tính tại **ngày khởi hành**, không phải ngày đặt vé. Một em bé 23 tháng khi đặt
  nhưng 24 tháng lúc bay thì phải mua vé CHILD. Hệ thống thật kiểm tra điều này và đây là
  nguồn khiếu nại rất phổ biến ở sân bay.

**Về INFANT mồ côi:** hệ thống thật luôn có trường *accompanying adult* (em bé đi kèm hành
khách nào) vì lúc làm thủ tục bay cần biết ai bế em bé nào. Model của bạn cần một cách nối
INFANT với booking (và lý tưởng là với người lớn đi kèm).

**Nguyên tắc kiến trúc: validate ở đâu?**
- `@Valid` / Bean Validation ở DTO → kiểm tra **hình thức** (không rỗng, đúng regex, đúng enum).
  Bạn đang làm tốt phần này (`PassengerRequest`).
- **Nghiệp vụ** (infant ≤ adult, tuổi khớp loại khách, chuyến bay còn bay được) → thuộc tầng
  **service/domain**, vì nó cần truy vấn DB và hiểu ngữ cảnh.
- Nguyên tắc vàng: **không bao giờ tin client**. Frontend đã chặn rồi thì backend vẫn phải
  chặn lại — vì API có thể bị gọi trực tiếp bằng Postman.

### KIẾN THỨC CẦN NẮM
- Bean Validation: custom constraint (`@Constraint` + `ConstraintValidator`), class-level
  validation (khi luật liên quan nhiều field cùng lúc).
- Tách `BookingValidator` thành class riêng — Single Responsibility. `BookingServiceImp` của bạn
  đang dài ~360 dòng và ôm quá nhiều việc.
- `Period.between(dateOfBirth, departureDate).getYears()`.

### HƯỚNG LÀM
- Tạo class `BookingValidator` (hoặc `BookingRuleChecker`) riêng, `createBooking` gọi nó ở
  dòng đầu tiên. Đưa vào đó: số infant ≤ số adult, phải có ≥ 1 adult, tổng khách ≤ 9,
  tuổi khớp `passengerType` (tính theo `flight.departureTime`), không trùng ghế trong request,
  chuyến bay `SCHEDULED` và `departureTime > now`, chiều về sau chiều đi.
- Quyết định cách xử lý INFANT: hoặc thêm `booking_id` vào `PassengerEntity`, hoặc tạo
  `passenger_ticket` cho infant với `seat_id = NULL` và giá = 10% vé người lớn. Cách 2 sát
  nghiệp vụ thật hơn (em bé vẫn là hành khách có vé, chỉ là không có ghế) — nhưng nhớ kiểm tra
  lại: cột `seat_id` của bạn có cho phép NULL không, và các query `JOIN FETCH t.seat`
  (`BookingRepository.java:20-28`) dùng `JOIN` thường sẽ **âm thầm loại bỏ** vé không có ghế.
  Đây là bẫy JPA rất hay gặp: `JOIN` vs `LEFT JOIN`. Hãy tự thử để thấy.
- Nếu `returnFlightId == null` mà có `returnSeatNumber` → nên báo lỗi rõ ràng thay vì im lặng.

### TỰ KIỂM CHỨNG
Dùng Postman gọi thẳng API (bỏ qua frontend) với các payload xấu:
- 1 ADULT + 3 INFANT → phải lỗi 4005
- Toàn INFANT, không ADULT → phải lỗi 4001
- 12 hành khách → phải lỗi
- `passengerType = INFANT` nhưng `dateOfBirth = 1990-01-01` → phải lỗi
- 2 hành khách cùng `runSeatNumber = "12A"` → phải lỗi
- `runFlightId` là chuyến đã `departureTime` trong quá khứ → phải lỗi
- Đặt booking có infant → query DB: `SELECT * FROM passengers p JOIN ... WHERE booking_id = ?`
  phải thấy em bé.

### XONG KHI NÀO
Không payload xấu nào lọt qua; INFANT truy vết được từ booking; logic validate nằm ở class riêng
chứ không nhét trong `createBooking`.

---

# CHẶNG 6 — Hoàn thiện API & vận hành 🟡

### HIỆN TRẠNG
- `BookingResponse` chỉ có 6 trường, **không có** danh sách hành khách, ghế, chuyến bay, hành lý
  → frontend không hiển thị nổi màn hình "chi tiết đặt chỗ".
- Không có API xem chi tiết 1 booking, không có API khách tự hủy booking.
- `/my-bookings` không phân trang, không lọc `isDeleted` (cột đã có ở `BookingEntity.java:39`).
- `bookingCode` = 8 ký tự đầu UUID, không kiểm tra trùng, không có UNIQUE ở DB.
- `cancelExpiredBookings` (`BookingRepository.java:53-64`) dùng nháy kép `"CANCELLED"` — chỉ chạy
  được khi MySQL không bật `ANSI_QUOTES`; và `@Modifying` thiếu `clearAutomatically = true`
  (entity cũ còn trong persistence context sau khi update thẳng bằng SQL).
- `flights.available_seats` **không bao giờ bị trừ** sau khi CONFIRMED, trong khi
  `searchAvailableFlights` lại lọc theo cột này → dữ liệu lệch dần theo thời gian.
- `BookingServiceImp` trộn `@Autowired` field injection với `@AllArgsConstructor` — không nhất quán.
- `SecurityConfig` `permitAll` toàn bộ (bạn đã biết, chờ merge nhánh auth).

### THỊ TRƯỜNG LÀM SAO
- **PNR code** trong ngành là 6 ký tự chữ-số (VD `ABC123`), **luôn có UNIQUE ở DB**, và sinh
  theo cách tránh nhầm lẫn thị giác (bỏ chữ `O`/số `0`, `I`/`1` — vì nhân viên sân bay phải đọc
  qua điện thoại). UUID 8 ký tự của bạn có xác suất trùng nhỏ nhưng khác 0; nguyên tắc là
  **để DB chặn bằng UNIQUE rồi retry**, chứ không cầu may.
- **Denormalized counter** (`available_seats`) là con dao hai lưỡi: nhanh khi đọc nhưng dễ lệch.
  Hệ thống thật hoặc dùng transaction nghiêm ngặt để cập nhật, hoặc tính lại định kỳ bằng job
  đối soát. **Đã denormalize thì phải có cơ chế giữ đồng bộ** — đây là điểm rất đáng nói khi
  bảo vệ đồ án.
- **Constructor injection** là khuyến nghị chính thức của Spring (field injection khiến không
  test được bằng `new`, và che giấu việc class đang phụ thuộc quá nhiều thứ — chính
  `BookingServiceImp` với 8 dependency là dấu hiệu class ôm quá nhiều việc, nên tách).

### HƯỚNG LÀM
- Bổ sung `BookingDetailResponse` lồng danh sách hành khách + ghế + chuyến bay + hành lý.
  Cẩn thận **N+1 query**: dùng `JOIN FETCH` (bạn đã biết dùng ở `findByIdWithTickets`) và bật
  `spring.jpa.show-sql=true` để đếm số câu SQL thực sự chạy — bài học rất quan trọng về JPA.
- Thêm `GET /bookings/{id}` và `POST /bookings/{id}/cancel` (nhớ chỉ cho hủy khi `PENDING`,
  và nhả ghế Redis + DB khi hủy).
- `/my-bookings` → trả `PageResponse` (bạn đã có sẵn class này, đang dùng ở flight search).
- Thêm UNIQUE cho `booking_code`, bắt `DataIntegrityViolationException` và sinh lại.
- Sửa nháy kép → nháy đơn trong native query, thêm `clearAutomatically = true`.
- Chuyển toàn bộ sang constructor injection (`final` + `@RequiredArgsConstructor`), rồi cân nhắc
  tách `BookingServiceImp` thành `BookingService` (điều phối) + `TicketPricingService` (tính giá)
  + `BookingValidator` (kiểm tra).

### XONG KHI NÀO
Frontend có đủ dữ liệu render màn hình chi tiết; `/my-bookings` phân trang được; số câu SQL cho
1 lần gọi chi tiết booking là hằng số (không tăng theo số hành khách).

---

# Checklist tổng — in ra và tick dần

```
CHẶNG 0 — Vá nhanh
[ ] 0.1 Sửa múi giờ VNPay → Asia/Ho_Chi_Minh
[ ] 0.2 Gửi email đúng địa chỉ khách
[ ] 0.3 .get() → orElseThrow(FLIGHT_NOT_FOUND)
[ ] 0.4 Log stacktrace ở GlobalExceptionHandler
[ ] 0.5 Secret ra biến môi trường + đổi lại secret đã lộ
[ ] 0.6 Dọn import/biến/hàm chết, đổi println → log

CHẶNG 1 — Chống bán trùng ghế  🔴 QUAN TRỌNG NHẤT
[ ] 1.1 Tái hiện được bug double-booking bằng tay (BẮT BUỘC làm trước khi sửa)
[ ] 1.2 Viết test 10 thread song song → thấy bug
[ ] 1.3 Thêm UNIQUE constraint ở DB
[ ] 1.4 Kiểm tra ghế đã bán trước khi tạo vé (+ bắt DataIntegrityViolationException)
[ ] 1.5 Chặn trùng ghế trong cùng 1 request
[ ] 1.6 findRandomSeat loại trừ ghế HELD trên Redis + lock ghế sau khi random
[ ] 1.7 Xóa cache static_seatmap khi ghế đổi trạng thái
[ ] 1.8 Test 10 thread chạy lại → chỉ 1 thành công  ✅

CHẶNG 2 — Vòng đời giữ ghế Redis
[ ] 2.1 Không gia hạn key với seatNumber null
[ ] 2.2 Kiểm tra kết quả expire()
[ ] 2.3 Nhả ghế Redis khi transaction DB rollback
[ ] 2.4 Thêm payment_deadline vào entity; cron nhả ghế khi hủy
[ ] 2.5 lockSeat kiểm tra ghế tồn tại + giới hạn số ghế/user
[ ] 2.6 Gom mọi mốc 15 phút về @ConfigurationProperties
[ ] 2.7 Fail-open cho luồng đọc seat map khi Redis chết

CHẶNG 3 — Thanh toán  🔴 LIÊN QUAN TIỀN THẬT
[ ] 3.1 Tạo bảng payments + lưu vnp_TxnRef khi tạo link
[ ] 3.2 Viết endpoint IPN /payments/vnpay-ipn
[ ] 3.3 vnpay-return chỉ hiển thị, không cập nhật DB
[ ] 3.4 Bỏ regex extractBookingId, tra cứu qua TxnRef
[ ] 3.5 Đối chiếu vnp_Amount với totalAmount
[ ] 3.6 Xử lý ca tiền về sau khi booking đã hủy (log + cảnh báo)
[ ] 3.7 Sửa multiply(100) đúng thứ tự
[ ] 3.8 Test gọi IPN 3 lần → không có tác dụng phụ  ✅

CHẶNG 4 — Kafka
[ ] 4.1 Bỏ catch nuốt lỗi, cấu hình DefaultErrorHandler + DLQ
[ ] 4.2 Consumer idempotent
[ ] 4.3 Bắn Kafka sau khi DB commit (AFTER_COMMIT)
[ ] 4.4 Ghi vào tài liệu: hạn chế còn lại + hướng Outbox Pattern

CHẶNG 5 — Validation nghiệp vụ
[ ] 5.1 Tách BookingValidator
[ ] 5.2 Luật infant/adult/child + tuổi theo ngày bay + tối đa 9 khách
[ ] 5.3 Kiểm tra trạng thái & thời gian chuyến bay
[ ] 5.4 Xử lý INFANT không còn mồ côi

CHẶNG 6 — Hoàn thiện
[ ] 6.1 BookingDetailResponse đầy đủ (chú ý N+1)
[ ] 6.2 API chi tiết + hủy booking
[ ] 6.3 Phân trang /my-bookings
[ ] 6.4 UNIQUE booking_code
[ ] 6.5 Sửa native query nháy kép + clearAutomatically
[ ] 6.6 Đồng bộ available_seats
[ ] 6.7 Constructor injection + tách nhỏ BookingServiceImp
```

---

# Bộ câu hỏi tự vấn (và rất có thể bị hỏi khi phỏng vấn)

Sau mỗi chặng, tự trả lời **thành tiếng, không nhìn code**. Nếu ấp úng thì bạn mới sửa xong
chứ chưa hiểu:

1. Vì sao Redis lock không đủ để chống bán trùng ghế? Nếu Redis đảm bảo atomic rồi thì hỏng ở đâu?
2. Khác nhau giữa optimistic lock và pessimistic lock? Trong bài này bạn chọn cái nào, vì sao?
3. `@Transactional` có rollback được thao tác Redis không? Vì sao không? Vậy xử lý thế nào?
4. Vì sao không được cập nhật đơn hàng ở `returnUrl` mà phải dùng IPN?
5. Idempotency là gì? Vì sao endpoint IPN bắt buộc phải idempotent? Bạn hiện thực nó bằng cách nào?
6. Kafka giao hàng theo ngữ nghĩa nào? Điều đó buộc consumer phải có tính chất gì?
7. Nếu DB commit thành công nhưng gửi Kafka thất bại thì sao? Pattern nào giải quyết? Tên nó là gì?
8. `KEYS *` và `SCAN` khác nhau ra sao? Vì sao production cấm `KEYS`?
9. Bạn cache seat map 15 phút — làm sao đảm bảo khách không thấy ghế đã bán là còn trống?
10. Nếu Redis chết, hệ thống nên chặn hay vẫn cho chạy? Câu trả lời có khác nhau giữa API đọc
    và API ghi không?
11. `JOIN` và `LEFT JOIN FETCH` khác nhau thế nào? Chuyện gì xảy ra với vé của em bé không có ghế?
12. N+1 query là gì? Bạn phát hiện nó bằng cách nào?

---

# Lời khuyên cuối

1. **Mỗi chặng một branch, một commit có ý nghĩa.** `fix: chống double booking bằng unique
   constraint + kiểm tra ghế đã bán` tốt hơn `fix bug`. Lịch sử Git là hồ sơ năng lực của bạn —
   người phỏng vấn có thể xem repo này.
2. **Ghi nhật ký học tập.** Tạo `docs/learning-log.md`, mỗi chặng viết 5–10 dòng: bug là gì, tôi
   tái hiện ra sao, tôi sửa thế nào, tôi học được gì. Khi phỏng vấn, kể được *quá trình* bao giờ
   cũng ăn điểm hơn khoe *kết quả*.
3. **Đừng sửa cái bạn chưa tái hiện được lỗi.** Nếu không dựng lại được bug, khả năng cao là
   bạn chưa hiểu nó, và bản sửa sẽ chỉ là mê tín.
4. **Không cần làm hết.** Chặng 1 + 3 làm thật kỹ có giá trị hơn nhiều so với làm hời hợt cả 6
   chặng. Những phần chưa làm thì ghi rõ trong README mục "Hạn chế đã biết & hướng mở rộng" —
   người có kinh nghiệm đánh giá rất cao ứng viên **biết mình chưa làm gì và vì sao**.
5. **Vẽ ra giấy trước khi code.** Đặc biệt Chặng 1 và 3: vẽ timeline hai người dùng song song,
   vẽ luồng tiền đi từ khách → VNPay → server bạn. Bug về race condition và thanh toán gần như
   luôn lộ ra ngay khi bạn vẽ chứ không phải khi bạn gõ code.
