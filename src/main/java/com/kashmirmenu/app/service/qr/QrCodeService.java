package com.kashmirmenu.app.service.qr;

public interface QrCodeService {

    byte[] generateQrCode(String content,
                          int width,
                          int height);
}