package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.ImageProcessor.Processed;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/** Real photos in, safe small JPEGs out: the checks use actual pixels and actual bytes. */
class ImageProcessorTest {

    // ----------------------------------------------------------------- building test files

    /** A picture that is red on the left half and blue on the right half. */
    private static BufferedImage halves(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out));
        return out.toByteArray();
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        return encode(halves(w, h, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    /** Inserts an EXIF segment right after the JPEG start marker, with the given orientation. */
    private static byte[] withExif(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00};
        return splice(jpeg, app1);
    }

    private static byte[] withComment(byte[] jpeg, String text) {
        byte[] t = text.getBytes(StandardCharsets.US_ASCII);
        byte[] seg = new byte[4 + t.length];
        seg[0] = (byte) 0xFF;
        seg[1] = (byte) 0xFE;
        seg[2] = (byte) ((t.length + 2) >> 8);
        seg[3] = (byte) ((t.length + 2) & 0xFF);
        System.arraycopy(t, 0, seg, 4, t.length);
        return splice(jpeg, seg);
    }

    private static byte[] splice(byte[] jpeg, byte[] segment) {
        byte[] out = new byte[jpeg.length + segment.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(segment, 0, out, 2, segment.length);
        System.arraycopy(jpeg, 2, out, 2 + segment.length, jpeg.length - 2);
        return out;
    }

    private static BufferedImage decode(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private static boolean looksRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 190 && ((rgb >> 8) & 0xFF) < 70 && (rgb & 0xFF) < 70;
    }

    private static boolean looksBlue(int rgb) {
        return (rgb & 0xFF) > 190 && ((rgb >> 16) & 0xFF) < 70 && ((rgb >> 8) & 0xFF) < 70;
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    /** True if a JPEG has an APP1 (EXIF/XMP) or COM (comment) segment before its pixel data. */
    private static boolean hasMetadataSegments(byte[] j) {
        int i = 2;
        while (i + 4 <= j.length && (j[i] & 0xFF) == 0xFF) {
            int marker = j[i + 1] & 0xFF;
            if (marker == 0xDA) {
                return false;
            }
            if (marker == 0xE1 || marker == 0xFE) {
                return true;
            }
            i += 2 + (((j[i + 2] & 0xFF) << 8) | (j[i + 3] & 0xFF));
        }
        return false;
    }

    private static void refused(byte[] input, String fragment) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> ImageProcessor.process(input));
        assertTrue(e.getMessage().toLowerCase().contains(fragment.toLowerCase()), e.getMessage());
    }

    // ----------------------------------------------------------------- accepted

    @Test
    void aJpegComesBackAsAJpegOfTheSameShape() throws Exception {
        Processed p = ImageProcessor.process(jpeg(300, 200));
        assertEquals("jpeg", ImageProcessor.sniff(p.jpeg()));
        assertEquals(300, p.width());
        assertEquals(200, p.height());
        BufferedImage back = decode(p.jpeg());
        assertEquals(300, back.getWidth());
        assertTrue(looksRed(back.getRGB(30, 100)));
        assertTrue(looksBlue(back.getRGB(270, 100)));
    }

    @Test
    void aPngBecomesAJpeg() throws Exception {
        Processed p = ImageProcessor.process(encode(halves(200, 100, BufferedImage.TYPE_INT_RGB), "png"));
        assertEquals("jpeg", ImageProcessor.sniff(p.jpeg()));
        assertEquals(200, p.width());
    }

    @Test
    void aBigPhotoIsShrunkToTheLimitKeepingItsProportions() throws Exception {
        Processed p = ImageProcessor.process(jpeg(3000, 2000));
        assertEquals(ImageProcessor.MAX_DIMENSION, p.width());
        assertEquals(800, p.height());
        BufferedImage back = decode(p.jpeg());
        assertEquals(1200, back.getWidth());
        assertEquals(800, back.getHeight());
    }

    @Test
    void aTallPhotoIsLimitedOnItsLongestSide() throws Exception {
        Processed p = ImageProcessor.process(jpeg(1500, 3000));
        assertEquals(1200, p.height());
        assertEquals(600, p.width());
    }

    @Test
    void aVeryWidePhotoIsDecodedAtReducedSizeAndStillComesOutRight() throws Exception {
        // 4800 wide triggers reduced-size decoding (memory protection); the result must still be 1200 wide.
        Processed p = ImageProcessor.process(jpeg(4800, 300));
        assertEquals(1200, p.width());
        assertEquals(75, p.height());
    }

    @Test
    void aSmallPhotoIsNeverBlownUp() throws Exception {
        Processed p = ImageProcessor.process(jpeg(200, 100));
        assertEquals(200, p.width());
        assertEquals(100, p.height());
    }

    @Test
    void transparencyBecomesWhiteNotBlack() throws Exception {
        BufferedImage clear = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);   // fully transparent
        Processed p = ImageProcessor.process(encode(clear, "png"));
        int px = decode(p.jpeg()).getRGB(50, 50);
        assertTrue(((px >> 16) & 0xFF) > 235 && ((px >> 8) & 0xFF) > 235 && (px & 0xFF) > 235, Integer.toHexString(px));
    }

    @Test
    void theOutputIsSmallForATypicalPhoto() throws Exception {
        assertTrue(ImageProcessor.process(jpeg(3000, 2000)).jpeg().length < 200_000);
    }

    // ----------------------------------------------------------------- privacy: metadata

    @Test
    void metadataIsStrippedIncludingAnythingHiddenInAComment() throws Exception {
        byte[] original = withComment(withExif(jpeg(300, 200), 1), "SECRET-GPS-51.5N-0.12W");
        assertTrue(contains(original, "SECRET-GPS"), "the test file really does carry the secret");
        assertTrue(hasMetadataSegments(original));

        byte[] stored = ImageProcessor.process(original).jpeg();

        assertFalse(contains(stored, "SECRET-GPS"));
        assertFalse(hasMetadataSegments(stored));
    }

    @Test
    void whatIsStoredIsAlwaysOurOwnFreshJpegNeverTheUploadedBytes() throws Exception {
        byte[] original = jpeg(300, 200);
        byte[] stored = ImageProcessor.process(original).jpeg();
        assertFalse(java.util.Arrays.equals(original, stored));
    }

    @Test
    void bytesAppendedAfterTheImageDoNotSurvive() throws Exception {
        byte[] polyglot = java.util.Arrays.copyOf(jpeg(300, 200), jpeg(300, 200).length + 40);
        byte[] tail = "<script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tail, 0, polyglot, polyglot.length - 40, tail.length);
        assertFalse(contains(ImageProcessor.process(polyglot).jpeg(), "<script>"));
    }

    // ----------------------------------------------------------------- rotation (phones)

    @Test
    void aPhonePortraitStoredSidewaysIsTurnedUprightBeforeTheNoteIsDiscarded() throws Exception {
        // Stored 300 wide x 100 tall, red left and blue right, with "rotate a quarter turn clockwise" (6).
        Processed p = ImageProcessor.process(withExif(jpeg(300, 100), 6));
        assertEquals(100, p.width());
        assertEquals(300, p.height());
        BufferedImage back = decode(p.jpeg());
        assertTrue(looksRed(back.getRGB(50, 20)), "left edge becomes the top");
        assertTrue(looksBlue(back.getRGB(50, 280)), "right edge becomes the bottom");
    }

    @Test
    void aQuarterTurnTheOtherWayAndUpsideDownAndMirroredAreAllHonoured() throws Exception {
        BufferedImage eight = decode(ImageProcessor.process(withExif(jpeg(300, 100), 8)).jpeg());
        assertEquals(100, eight.getWidth());
        assertTrue(looksBlue(eight.getRGB(50, 20)), "orientation 8: right edge becomes the top");
        assertTrue(looksRed(eight.getRGB(50, 280)), "orientation 8: left edge becomes the bottom");

        BufferedImage three = decode(ImageProcessor.process(withExif(jpeg(300, 100), 3)).jpeg());
        assertEquals(300, three.getWidth());
        assertTrue(looksBlue(three.getRGB(30, 50)), "orientation 3: turned half way round");
        assertTrue(looksRed(three.getRGB(270, 50)));

        BufferedImage two = decode(ImageProcessor.process(withExif(jpeg(300, 100), 2)).jpeg());
        assertTrue(looksBlue(two.getRGB(30, 50)), "orientation 2: mirrored left to right");
        assertTrue(looksRed(two.getRGB(270, 50)));
    }

    @Test
    void orientationOneAndNonsenseValuesLeaveThePhotoAsStored() throws Exception {
        for (int o : new int[]{1, 0, 9, 200}) {
            BufferedImage img = decode(ImageProcessor.process(withExif(jpeg(300, 100), o)).jpeg());
            assertEquals(300, img.getWidth(), "orientation " + o);
            assertTrue(looksRed(img.getRGB(30, 50)), "orientation " + o);
        }
    }

    @Test
    void rotationWorksAfterShrinkingToo() throws Exception {
        Processed p = ImageProcessor.process(withExif(jpeg(3000, 2000), 6));
        assertEquals(800, p.width());
        assertEquals(1200, p.height());
    }

    @Test
    void brokenExifIsIgnoredNotFatal() throws Exception {
        byte[] bad = splice(jpeg(300, 100), new byte[]{(byte) 0xFF, (byte) 0xE1, 0x00, 0x08, 'E', 'x', 'i', 'f', 0, 0});
        assertEquals(300, ImageProcessor.process(bad).width());
    }

    // ----------------------------------------------------------------- refused: what is it?

    @Test
    void notAnImageIsRefused() {
        refused(new byte[0], "empty");
        refused("just some text".getBytes(StandardCharsets.UTF_8), "JPEG or PNG");
        refused("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8), "JPEG or PNG");
        refused("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8), "JPEG or PNG");
        refused("%PDF-1.7 fake".getBytes(StandardCharsets.US_ASCII), "JPEG or PNG");
        refused("GIF89a\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1), "JPEG or PNG");
        refused(new byte[]{'M', 'Z', (byte) 0x90, 0}, "JPEG or PNG");      // a Windows program
    }

    @Test
    void aFileThatClaimsToBeAnImageButIsNotIsRefused() {
        byte[] fakeJpeg = new byte[200];
        fakeJpeg[0] = (byte) 0xFF;
        fakeJpeg[1] = (byte) 0xD8;
        fakeJpeg[2] = (byte) 0xFF;
        refused(fakeJpeg, "incomplete");   // no image data and no end marker
        byte[] fakePng = new byte[200];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, fakePng, 0, 8);
        refused(fakePng, "could not be read");
    }

    @Test
    void aTruncatedPhotoIsRefusedNotStoredHalfGrey() throws Exception {
        byte[] whole = jpeg(600, 400);
        refused(java.util.Arrays.copyOf(whole, whole.length / 3), "incomplete");
        refused(java.util.Arrays.copyOf(whole, whole.length - 3), "incomplete");   // even the last bytes missing
        refused(java.util.Arrays.copyOf(whole, 50), "incomplete");
    }

    @Test
    void aTruncatedPngIsRefusedToo() throws Exception {
        byte[] whole = encode(halves(600, 400, BufferedImage.TYPE_INT_RGB), "png");
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> ImageProcessor.process(java.util.Arrays.copyOf(whole, whole.length / 2)));
        assertTrue(e.getMessage().contains("could not be read"), e.getMessage());
    }

    @Test
    void dataAFileCarriesAfterTheImageIsAcceptedAndDiscarded() throws Exception {
        // Some phones append a thumbnail or a short video after the JPEG's end marker.
        byte[] image = jpeg(300, 200);
        byte[] withTrailer = java.util.Arrays.copyOf(image, image.length + 5000);
        byte[] trailer = "MOTION-PHOTO-VIDEO".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(trailer, 0, withTrailer, image.length, trailer.length);

        Processed p = ImageProcessor.process(withTrailer);

        assertEquals(300, p.width());
        assertFalse(contains(p.jpeg(), "MOTION-PHOTO"));
    }

    @Test
    void theEndMarkerIsFoundPastMetadataSegments() throws Exception {
        assertTrue(ImageProcessor.hasEndMarker(jpeg(100, 100)));
        assertTrue(ImageProcessor.hasEndMarker(withComment(withExif(jpeg(100, 100), 6), "hello")));
        assertFalse(ImageProcessor.hasEndMarker(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
        assertFalse(ImageProcessor.hasEndMarker(new byte[]{(byte) 0xFF, (byte) 0xD8}));
    }

    // ----------------------------------------------------------------- refused: size

    @Test
    void anOversizedUploadIsRefusedBeforeAnythingIsDecoded() {
        byte[] huge = new byte[ImageProcessor.MAX_UPLOAD_BYTES + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        refused(huge, "too large");
    }

    @Test
    void aTinyFileThatDeclaresAHugeCanvasIsRefusedWithoutBuildingIt() {
        // A 30000 x 30000 PNG header: the file is a few dozen bytes but would need 3.6 GB of memory to decode.
        BadRequestException e = assertThrows(BadRequestException.class, () -> ImageProcessor.process(pngHeader(30000, 30000)));
        assertTrue(e.getMessage().contains("pixels") || e.getMessage().contains("could not be read"), e.getMessage());
    }

    @Test
    void aHugeCanvasIsSpecificallyReportedAsTooManyPixelsWhenTheHeaderIsReadable() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> ImageProcessor.process(pngHeader(30000, 30000)));
        assertTrue(e.getMessage().contains("pixels"), "expected the pixel limit message, got: " + e.getMessage());
    }

    private static byte[] pngHeader(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.write(new byte[]{'I', 'H', 'D', 'R'});
        ihdr.write(new byte[]{(byte) (w >> 24), (byte) (w >> 16), (byte) (w >> 8), (byte) w});
        ihdr.write(new byte[]{(byte) (h >> 24), (byte) (h >> 16), (byte) (h >> 8), (byte) h});
        ihdr.write(new byte[]{8, 2, 0, 0, 0});
        byte[] body = ihdr.toByteArray();
        out.write(new byte[]{0, 0, 0, 13});
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(body);
        long c = crc.getValue();
        out.write(new byte[]{(byte) (c >> 24), (byte) (c >> 16), (byte) (c >> 8), (byte) c});
        return out.toByteArray();
    }

    // ----------------------------------------------------------------- the helpers

    @Test
    void sniffingReadsOnlyTheFirstBytes() throws Exception {
        assertEquals("jpeg", ImageProcessor.sniff(jpeg(10, 10)));
        assertEquals("png", ImageProcessor.sniff(encode(halves(10, 10, BufferedImage.TYPE_INT_RGB), "png")));
        assertNull(ImageProcessor.sniff(new byte[]{1, 2, 3}));
        assertNull(ImageProcessor.sniff(new byte[0]));
    }

    @Test
    void exifOrientationIsReadFromEitherByteOrderAndNeverThrows() throws Exception {
        assertEquals(6, ImageProcessor.exifOrientation(withExif(jpeg(30, 30), 6)));
        assertEquals(1, ImageProcessor.exifOrientation(jpeg(30, 30)));
        assertEquals(1, ImageProcessor.exifOrientation(new byte[]{(byte) 0xFF, (byte) 0xD8}));
        assertEquals(1, ImageProcessor.exifOrientation(new byte[0]));
        assertEquals(1, ImageProcessor.exifOrientation(new byte[]{1, 2, 3, 4, 5}));
    }
}
