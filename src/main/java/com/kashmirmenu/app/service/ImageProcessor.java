package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Turns an uploaded photo into the one safe, small form the app stores: a JPEG of at most
 * {@value #MAX_DIMENSION} pixels on its longest side. Pure (JDK only), so it is tested with real pixels.
 *
 * <p>What it guarantees, and why:
 * <ul>
 *   <li><b>The bytes decide what a file is, not its name or the browser's claim.</b> Only a real JPEG or
 *       PNG (by its first bytes) is accepted, and it must then actually decode as one. HTML, SVG, PDFs
 *       and a script renamed to .jpg are all refused.</li>
 *   <li><b>Always re-encoded.</b> What is stored is a fresh JPEG we wrote, never the uploaded bytes, so
 *       nothing hidden in the original (a second file appended after the image, scripts, comments) survives.</li>
 *   <li><b>Metadata is stripped</b>, including the GPS position and camera details phones embed, which a
 *       restaurant would otherwise publish to every diner by accident.</li>
 *   <li><b>Rotation is honoured first.</b> Phones store portrait photos sideways with a note in the
 *       metadata saying how to turn them. Dropping metadata without applying that note would show every
 *       portrait photo on its side, so the EXIF orientation is applied, then discarded.</li>
 *   <li><b>Memory is bounded.</b> Dimensions are read from the header before any pixels, a photo above
 *       {@value #MAX_SOURCE_PIXELS} pixels is refused (a tiny file can declare a huge canvas), and large
 *       photos are decoded at reduced size.</li>
 *   <li><b>Transparency becomes white</b> (JPEG has none), so a PNG logo does not turn black.</li>
 * </ul>
 */
public final class ImageProcessor {

    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    public static final int MAX_SOURCE_PIXELS = 24_000_000;
    public static final int MAX_DIMENSION = 1200;
    public static final float JPEG_QUALITY = 0.82f;

    private ImageProcessor() {
    }

    public record Processed(byte[] jpeg, int width, int height) {
    }

    public static Processed process(byte[] input) {
        if (input == null || input.length == 0) {
            throw bad("The file is empty.");
        }
        if (input.length > MAX_UPLOAD_BYTES) {
            throw bad("The file is too large (limit " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB).");
        }
        String format = sniff(input);
        if (format == null) {
            throw bad("Please choose a JPEG or PNG photo.");
        }

        // Java's JPEG decoder is forgiving: a photo cut off part-way (an interrupted upload) is decoded as
        // far as it goes and the rest filled grey, with no error. Insist the JPEG really reaches its end.
        if ("jpeg".equals(format) && !hasEndMarker(input)) {
            throw bad("That photo looks incomplete; the upload may have been cut short. Please try again.");
        }

        BufferedImage decoded;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (in == null || !readers.hasNext()) {
                throw bad("That file could not be read as a photo.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > MAX_SOURCE_PIXELS) {
                    throw bad("That photo is too large in pixels. Please use one under "
                            + (MAX_SOURCE_PIXELS / 1_000_000) + " megapixels.");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int subsample = Math.max(1, Math.max(w, h) / (MAX_DIMENSION * 2));
                if (subsample > 1) {
                    param.setSourceSubsampling(subsample, subsample, 0, 0);
                }
                decoded = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Deliberately vague: the reason a decoder gave is of no use to an attacker or an owner.
            throw bad("That file could not be read as a photo. Try saving it as a JPEG or PNG and uploading again.");
        }
        if (decoded == null) {
            throw bad("That file could not be read as a photo.");
        }

        int orientation = "jpeg".equals(format) ? exifOrientation(input) : 1;
        BufferedImage ready = orient(scaleDown(flatten(decoded)), orientation);
        return new Processed(encodeJpeg(ready), ready.getWidth(), ready.getHeight());
    }

    // ------------------------------------------------------------------ what is it?

    /** "jpeg", "png" or null, from the first bytes alone. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        return null;
    }

    /**
     * Whether a JPEG reaches its end-of-image marker. Segments are skipped by their declared length and
     * the compressed data between them is scanned for the next marker, so data a camera appends AFTER the
     * image (a thumbnail, a motion-photo video) does not matter: the first end marker is what counts.
     */
    static boolean hasEndMarker(byte[] b) {
        int p = 2;
        while (p < b.length - 1) {
            if ((b[p] & 0xFF) != 0xFF) {
                p++;                                      // compressed image data
                continue;
            }
            int m = b[p + 1] & 0xFF;
            if (m == 0xFF) {
                p++;                                      // padding before a marker
            } else if (m == 0x00 || (m >= 0xD0 && m <= 0xD7) || m == 0x01 || m == 0xD8) {
                p += 2;                                   // a stuffed byte, restart or standalone marker
            } else if (m == 0xD9) {
                return true;
            } else {
                if (p + 4 > b.length) {
                    return false;
                }
                p += 2 + (((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF));
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ pixels

    /** Draws onto an opaque white RGB canvas: removes transparency and gives every input one pixel format. */
    private static BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage scaleDown(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= MAX_DIMENSION) {
            return src;
        }
        double factor = (double) MAX_DIMENSION / longest;
        int nw = Math.max(1, (int) Math.round(w * factor));
        int nh = Math.max(1, (int) Math.round(h * factor));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Applies an EXIF orientation (1 to 8) so the pixels are the right way up. 1 is "as stored";
     * 3 is upside down; 6 and 8 are a quarter turn clockwise and anticlockwise; 2, 4, 5 and 7 are
     * mirrored variants. Any other value is treated as 1.
     */
    static BufferedImage orient(BufferedImage src, int orientation) {
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8;
        if (orientation < 2 || orientation > 8) {
            return src;
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                switch (orientation) {
                    case 2 -> out.setRGB(w - 1 - x, y, rgb);          // mirror left-right
                    case 3 -> out.setRGB(w - 1 - x, h - 1 - y, rgb);  // upside down
                    case 4 -> out.setRGB(x, h - 1 - y, rgb);          // mirror top-bottom
                    case 5 -> out.setRGB(y, x, rgb);                  // transpose
                    case 6 -> out.setRGB(h - 1 - y, x, rgb);          // quarter turn clockwise
                    case 7 -> out.setRGB(h - 1 - y, w - 1 - x, rgb);  // transverse
                    case 8 -> out.setRGB(y, w - 1 - x, rgb);          // quarter turn anticlockwise
                    default -> { }
                }
            }
        }
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(out);
                // null metadata: nothing from the original (EXIF, GPS, comments, thumbnails) is carried over
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw bad("That photo could not be processed.");
        } finally {
            writer.dispose();
        }
    }

    // ------------------------------------------------------------------ EXIF orientation

    /** The EXIF orientation of a JPEG, 1 to 8, or 1 when absent or malformed. Never throws. */
    static int exifOrientation(byte[] jpeg) {
        try {
            int i = 2;                                   // after FF D8
            while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
                int marker = jpeg[i + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    return 1;                            // start of pixel data: no EXIF before it
                }
                int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
                if (length < 2) {
                    return 1;
                }
                if (marker == 0xE1 && length >= 16 && matches(jpeg, i + 4, 'E', 'x', 'i', 'f', 0, 0)) {
                    return readOrientation(jpeg, i + 10, i + 2 + length);
                }
                i += 2 + length;
            }
        } catch (RuntimeException ignored) {
            // malformed metadata is not an error: show the photo as stored
        }
        return 1;
    }

    private static int readOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end || end > b.length) {
            return 1;
        }
        boolean little = b[tiff] == 'I' && b[tiff + 1] == 'I';
        if (!little && !(b[tiff] == 'M' && b[tiff + 1] == 'M')) {
            return 1;
        }
        int ifd = tiff + (int) u32(b, tiff + 4, little);
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int entries = u16(b, ifd, little);
        for (int e = 0; e < entries; e++) {
            int at = ifd + 2 + e * 12;
            if (at + 12 > end) {
                return 1;
            }
            if (u16(b, at, little) == 0x0112) {
                int value = u16(b, at + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static boolean matches(byte[] b, int at, int... expected) {
        if (at + expected.length > b.length) {
            return false;
        }
        for (int k = 0; k < expected.length; k++) {
            if ((b[at + k] & 0xFF) != expected[k]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int x = b[at] & 0xFF, y = b[at + 1] & 0xFF;
        return little ? (y << 8) | x : (x << 8) | y;
    }

    private static long u32(byte[] b, int at, boolean little) {
        long a = b[at] & 0xFF, c = b[at + 1] & 0xFF, d = b[at + 2] & 0xFF, e = b[at + 3] & 0xFF;
        return little ? (e << 24) | (d << 16) | (c << 8) | a : (a << 24) | (c << 16) | (d << 8) | e;
    }

    private static BadRequestException bad(String message) {
        return new BadRequestException(message);
    }
}
