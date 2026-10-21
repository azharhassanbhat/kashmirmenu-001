package com.kashmirmenu.app.service.qr;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.EncodeHintType;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
@Service
public class QrCodeServiceImpl implements QrCodeService {

    @Override
    public byte[] generateQrCode(String content, int width, int height) {

        try {

            QRCodeWriter qrCodeWriter = new QRCodeWriter();

            // Level M (about 15% of the code can be damaged and it still scans) instead of the default L (7%):
            // a card on a restaurant table gets wiped, scuffed and splashed. The same address is encoded, so
            // codes already printed keep working; only the dot pattern is a little denser.
            java.util.Map<EncodeHintType, Object> hints = new java.util.EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix bitMatrix = qrCodeWriter.encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    width,
                    height,
                    hints
            );

            ByteArrayOutputStream outputStream =
                    new ByteArrayOutputStream();

            MatrixToImageWriter.writeToStream(
                    bitMatrix,
                    "PNG",
                    outputStream
            );

            return outputStream.toByteArray();

        } catch (WriterException | IOException ex) {

            log.error("Unable to generate QR Code", ex);

            throw new RuntimeException("Failed to generate QR Code", ex);
        }
    }
}