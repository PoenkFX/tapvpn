# TapVPN 2.0

App Android kiểm thử: bật VPN là chặn toàn bộ lưu lượng mạng (không máy chủ, không chuyển gói, không xử lý DNS). Nút nổi nằm trên mọi ứng dụng.

- Nút hiện **OFF** khi tắt, **ON** khi bật
- Đang đếm ngược thì chỉ hiện số (ví dụ `3.0`), không hiện ON/OFF
- Bấm giữa lúc đếm thì tắt ngay
- Giữ nút để ghim hoặc bỏ ghim vị trí
- Trong app: chỉnh cỡ nút 48–96 dp, bật hẹn giờ tắt 0.1–10.0 giây (bước 0.1 giây). Tắt hẹn giờ thì chạy đến khi bấm dừng
- Nút hơi trong suốt, không bấm xuyên

## Cài

File `tapvpn-2.0.apk`. Cho phép cài từ nguồn không xác định.

Lần đầu Android hỏi:

- **VPN**
- **Hiển thị trên ứng dụng khác**

## Build

Mở thư mục này bằng Android Studio, để nó tải SDK/Gradle, rồi Build APK.

`versionName` 2.0, `versionCode` 11, `minSdk` 26, `targetSdk` 34.
Ký release bằng `tapvpn.jks` (alias `tapvpn`). Repo này để riêng tư vì có keystore.
