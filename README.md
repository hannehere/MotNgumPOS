# MotNgum POS 🧋

Phần mềm **mã nguồn mở** quản lý đơn hàng cho quán nước: tự nhận đơn từ app **GrabMerchant**
(và các app bán hàng khác), rồi **in tem dán ly** ra máy in tem nhiệt (Xprinter, TSC, Gprinter…).

Có 2 cách chạy:

**A. Tự chạy trên tablet (khuyên dùng — không cần máy tính)**

```
 ┌──────────────────── Tablet / điện thoại Android ────────────────────┐
 │ GrabMerchant ──"Đơn mới"──► MotNgum: mở đơn → đọc chữ → tách món    │  WiFi/LAN   ┌──────────────┐
 │                              → lưu đơn → vẽ tem tiếng Việt → TSPL ──┼───────────► │ Xprinter     │
 │                              màn hình quản lý đơn ngay trong app    │  cổng 9100  │ XP-365B (LAN)│
 └─────────────────────────────────────────────────────────────────────┘             └──────────────┘
```

**B. Có máy tính ở quầy:** app Android chỉ đọc đơn rồi gửi về server Python (`server/`) trên máy tính;
máy tính tách món, in tem và có trang quản lý đơn trên trình duyệt. Dùng khi máy in cắm USB vào máy tính,
hoặc muốn xem đơn trên màn hình lớn.

## Cách lấy thông tin đơn

Ý tưởng “thấy thông báo → tự vào Grab → chụp màn hình lấy thông tin” được làm như sau:

1. **Nghe thông báo** (`NotificationListenerService`): khi GrabMerchant báo có đơn mới, agent bấm
   vào thông báo để mở đúng màn hình chi tiết đơn (có đánh thức màn hình).
2. **Đọc chữ trên màn hình** qua **Trợ năng (AccessibilityService)**: lấy trực tiếp chữ mà app hiển thị
   (mã đơn `GF-xxx`, tên món, size, % đường, đá, topping, ghi chú). Cách này **chính xác hơn OCR
   ảnh chụp** (không sai dấu tiếng Việt, không nhầm số), và tự cuộn để đọc hết danh sách món dài.
3. **Chụp màn hình** (Android 11+): ảnh đơn được gửi kèm để lưu đối chiếu. Nếu app không cho đọc chữ,
   server sẽ **OCR ảnh** bằng Tesseract (tuỳ chọn).
4. App (chế độ A) hoặc server (chế độ B) **bóc tách món**, gộp các lần đọc (khi cuộn), chờ vài giây cho đủ
   món rồi **tự in mỗi ly một tem** (`GF-512 1/3`, `2/3`, `3/3`…). Ghi chú của món in trên tem của món đó;
   ghi chú chung của đơn in trên mọi tem.

> ⚠️ Đây là cách “đọc màn hình” không chính thức. Khi Grab đổi giao diện, có thể phải chỉnh từ khoá
> trong `android-agent/.../OrderParser.kt` (chế độ A) và `server/motngum/parser.py` (chế độ B) — 2 file
> dùng chung bộ mẫu test. Cách chính thống là **Grab Partner API / POS integration** (cần đăng ký
> làm đối tác với Grab) — server đã có API `POST /api/orders` để sau này nối vào dễ dàng.

## Thành phần

| Thư mục | Nội dung |
|---|---|
| `server/` | Server Python (FastAPI + SQLite + Pillow): API nhận đơn, parser, vẽ tem, lệnh in TSPL/ESC-POS, giao diện web |
| `android-agent/` | App Android (Kotlin, không thư viện ngoài): nghe thông báo, đọc màn hình, chụp màn hình; tách món, lưu đơn, vẽ tem và in thẳng ra máy in LAN (chế độ A) hoặc gửi về server (chế độ B) |
| `.github/workflows/build.yml` | CI: chạy test + build file APK (tải ở tab *Actions → Artifacts*) |

## Chế độ A: tự chạy trên tablet (không cần máy tính)

1. **Máy in:** cắm dây LAN từ XP-365B vào router (cùng mạng WiFi với tablet), để máy in ở **chế độ in tem**.
   In trang tự kiểm tra (self-test) để xem IP máy in. Nên đặt **IP cố định** cho máy in trong router
   (DHCP reservation) để IP không đổi sau khi mất điện.
2. **Cài app** lên tablet (xem mục *Cài app Android* bên dưới) và cấp quyền.
3. Mở app → **⚙ Cài đặt**:
   - Bật **“Tự chạy trên máy này”** (mặc định đã bật).
   - Nhập **IP máy in**, cổng `9100`.
   - Nhập **khổ tem** rộng × cao × khe (mm), ví dụ `50 × 30 × 2`, và tên quán.
   - **Lưu**.
4. Về màn hình chính → **↕ Căn khe tem** (máy in tự dò khe giữa các tem) → **🖨 In thử**.
5. Xong. Khi có đơn Grab, app tự mở đơn, đọc món và in tem. Màn hình chính của app có 3 tab
   *Mới / Đã in – đang pha / Xong*, nút **In lại**, **Xem tem** (bấm 1 tem để in riêng tem đó),
   **Tạo đơn** tại quầy, **📷 Ảnh** chụp đơn, **📄 Chữ gốc** (chữ đọc từ màn hình Grab — copy gửi
   người chỉnh parser nếu tách món sai). Đơn cũ hơn 30 ngày và ảnh cũ hơn 3 ngày được tự xoá.

## Chế độ B: có máy tính ở quầy

### 1. Cài server

**Windows:** cài [Python 3.10+](https://www.python.org/downloads/) (tick “Add to PATH”), rồi bấm đúp
`server/start-windows.bat`.

**Linux / macOS:**

```bash
cd server
./start.sh
```

Mở trình duyệt: `http://localhost:8686` — màn hình quản lý đơn. Các máy khác trong cùng WiFi vào
`http://<IP-máy-tính>:8686`.

Lần đầu chạy sẽ tạo `server/config.json` (copy từ `config.example.json`). Các mục quan trọng:

```jsonc
{
  "shop_name": "Một Ngụm",
  "api_key": "doi-ma-nay-di",          // ĐỔI mã này, nhập giống trong app Android
  "auto_print": true,                  // tự in khi có đơn Grab
  "print_delay_seconds": 4,            // chờ đọc đủ món rồi mới in
  "printer": {
    "language": "tspl",                // tspl: máy in tem | escpos: máy in bill
    "transport": "windows",            // dryrun | tcp | device | cups | windows
    "windows_name": "Xprinter XP-365B",// tên máy in trong Control Panel (Windows)
    "host": "192.168.1.100", "port": 9100,   // máy in LAN/WiFi
    "device": "/dev/usb/lp0",          // Linux USB
    "cups_name": "Xprinter"            // Linux/macOS qua CUPS
  },
  "label": { "width_mm": 50, "height_mm": 30, "gap_mm": 2, "dpi": 203 }
}
```

- Mặc định `transport: "dryrun"` → **không in thật**, chỉ lưu tem ra `server/data/prints/*.png` để xem thử.
- Bấm **“🖨 In tem thử”** trên giao diện web để kiểm tra máy in.
- Tem được vẽ thành ảnh rồi gửi lệnh `BITMAP`, nên **in được tiếng Việt có dấu** trên mọi máy in TSPL.
- Khổ tem phổ biến cho ly: 50×30, 40×30, 35×25 mm — sửa `label.width_mm/height_mm` cho đúng cuộn tem.

### Cấu hình cho Xprinter XP-365B

XP-365B in nhiệt trực tiếp 203 dpi, có cổng USB + LAN và 2 chế độ **in tem / in bill**.
Để in tem ly, máy phải ở **chế độ in tem (label)** — cách chuyển chế độ xem hướng dẫn đi kèm máy
hoặc công cụ cài đặt của Xprinter.

**Cách 1 – cắm dây LAN (khuyên dùng, không cần driver, chạy được trên mọi hệ điều hành):**
cắm máy in vào router, in trang tự kiểm tra (self-test) của máy để xem địa chỉ IP, rồi:

```json
"printer": { "language": "tspl", "transport": "tcp", "host": "192.168.1.100", "port": 9100 }
```

**Cách 2 – cắm USB vào máy tính Windows:** cài driver Xprinter, xem đúng tên máy in trong
*Control Panel → Devices and Printers*, rồi:

```json
"printer": { "language": "tspl", "transport": "windows", "windows_name": "Xprinter XP-365B" }
```

Khổ tem: sửa `label.width_mm`, `label.height_mm`, `label.gap_mm` đúng với cuộn tem đang dùng
(ví dụ 50×30 mm, khe 2 mm). Khi mới lắp cuộn tem hoặc đổi khổ, bấm **“↕ Căn khe tem”** trên web để máy
tự dò khe giữa các tem (nếu không, tem sẽ in lệch/lấn sang tem sau), sau đó bấm **“🖨 In tem thử”**.

## Cài app Android (máy đang chạy GrabMerchant)

1. Tải APK: tab **Actions** của repo → lần build mới nhất → *motngum-agent-apk*; hoặc tự build:
   ```bash
   cd android-agent
   ./gradlew assembleRelease
   ```
2. Cài APK, mở **MotNgum Agent**:
   - Chế độ A: vào **⚙ Cài đặt**, nhập IP máy in + khổ tem (xem trên). Chế độ B: tắt “Tự chạy”, nhập địa chỉ
     máy chủ (`http://192.168.1.10:8686`) và **API key** → **Lưu** → **Kiểm tra kết nối**.
   - Bấm **“1. Cấp quyền đọc thông báo”** → bật MotNgum.
   - **Android 13+**: bấm *“Cho phép cài đặt hạn chế”* → menu ⋮ → **Allow restricted settings**
     (bắt buộc với app cài ngoài CH Play mới bật được Trợ năng).
   - Bấm **“2. Bật Trợ năng”** → *MotNgum – đọc đơn hàng* → Bật.
   - **Tắt tối ưu pin** cho MotNgum Agent (và cho GrabMerchant) để không bị hệ thống tắt ngầm.
3. Khuyến nghị dùng 1 máy riêng ở quầy: cắm sạc, **tắt khoá màn hình**, để màn hình luôn sáng
   (Android không cho app tự mở khoá màn hình có mật khẩu).

### Thêm ShopeeFood, Be…

Trong app Android, thêm package của app đó vào ô *“App bán hàng cần theo dõi”* và sửa *“Mẫu mã đơn”*.
Trên server thêm một mục trong `config.json → sources` (tên nguồn, nhãn in trên tem, regex mã đơn).

## Chỉnh parser khi Grab đổi giao diện

Chế độ A: mở đơn tách sai trong app → **📄 Chữ gốc** → **Copy**, gửi đoạn chữ đó để chỉnh
`OrderParser.kt` (thêm mẫu vào `OrderParserTest.kt`). Nếu đơn không hiện trong app, bật
**“Chế độ dò lỗi”** trong Cài đặt rồi mở đơn: nhật ký trong Cài đặt sẽ hiện các dòng chữ đã đọc.

Chế độ B:

1. Bật **“Chế độ dò lỗi”** trong app Android, mở 1 đơn trên GrabMerchant.
2. Trên web, tab **Nhật ký agent** → xem các dòng chữ đã đọc → bấm *“Đưa sang Thử bóc đơn”*.
3. Tab **Thử bóc đơn** hiển thị kết quả bóc tách ngay. Sửa từ khoá trong `server/motngum/parser.py`
   (`STOP_WORDS`, `IGNORE_EXACT`, regex), thêm mẫu vào `server/tests/test_parser.py` và chạy:
   ```bash
   cd server
   python -m pytest -q
   ```

## Tạo đơn tại quầy

Tab **Tạo đơn**, mỗi dòng một món:

```
2x Trà sữa trân châu | Size L, 50% đường | ghi chú: ít ngọt
1x Cà phê muối | Ít đá
```

## API

| Method | Đường dẫn | Mô tả |
|---|---|---|
| `GET` | `/api/orders?status=new` | Danh sách đơn |
| `POST` | `/api/orders` | Tạo đơn (JSON `items` hoặc `text` nhập nhanh) — dùng để nối POS/webhook khác |
| `PATCH` | `/api/orders/{id}` | Đổi trạng thái `new/printed/done/cancelled` |
| `POST` | `/api/orders/{id}/print` | In (lại) tất cả hoặc một số tem `{"labels":[2]}` |
| `GET` | `/api/orders/{id}/labels/{n}.png` | Xem trước tem |
| `POST` | `/api/agent/notification` · `/api/agent/screen` · `/api/agent/screenshot` | Dành cho app Android (header `X-Api-Key`) |
| `POST` | `/api/parse` | Thử bóc tách chữ màn hình |

Tài liệu tương tác: `http://localhost:8686/docs`.

## Tham khảo mã nguồn mở

Dự án tự viết gọn cho đúng nhu cầu “nhận đơn → in tem ly”, tham khảo cách làm từ:

- [DantSu/ESCPOS-ThermalPrinter-Android](https://github.com/DantSu/ESCPOS-ThermalPrinter-Android) — in ESC/POS qua Bluetooth/USB/TCP trên Android (hướng mở rộng: in thẳng từ điện thoại không cần máy tính).
- [xprinter_flutter](https://pub.dev/packages/xprinter_flutter) — lệnh TSPL cho máy in tem Xprinter.
- [farminos/open-escpos-print-service](https://github.com/farminos/open-escpos-print-service) — Android PrintService cho máy in nhiệt.
- POS nhà hàng đầy đủ nếu cần mở rộng (bàn, bếp, báo cáo): [URY (ERPNext)](https://github.com/genforge/ury),
  [satisfecho/pos](https://github.com/satisfecho/pos), [TastyIgniter](https://github.com/tastyigniter/TastyIgniter).
  Có thể đẩy đơn sang các hệ thống này qua `POST /api/orders` hoặc ngược lại.

## Lộ trình

- [x] Tự chạy trên tablet, in thẳng ra máy in LAN (không cần máy tính)
- [ ] In qua Bluetooth cho máy in không có cổng LAN
- [ ] Menu + giá bán, báo cáo doanh thu theo ngày
- [ ] Mẫu parser cho ShopeeFood, Be
- [ ] Hàng đợi offline trên app Android khi mất mạng
