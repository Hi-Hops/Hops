package com.hihops.hops.inquiry.attachment;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** 전송된 PNG·JPEG를 검증하고 이미지 메타데이터 없이 PNG로 다시 인코딩한다. */
public final class AttachmentImageSanitizer {
    private static final int MAX_INPUT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 16_000_000L;

    private AttachmentImageSanitizer() {
    }

    /** 개인정보를 찾아 가리는 기능이 아니다. 가림은 이 입력을 전송하기 전에 수행해야 한다. */
    public static byte[] sanitize(byte[] input) throws IOException {
        if (input.length == 0 || input.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("이미지 파일 크기가 허용 범위를 벗어났습니다");
        }
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("지원하지 않는 이미지입니다");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw new IllegalArgumentException("지원하지 않는 이미지입니다");
                }
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("이미지 크기가 허용 범위를 벗어났습니다");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("이미지를 읽을 수 없습니다");
                }
                BufferedImage clean = new BufferedImage(width, height,
                        image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    image.getRGB(0, y, width, 1, row, 0, width);
                    if (image.getColorModel().hasAlpha()) {
                        for (int x = 0; x < width; x++) {
                            if ((row[x] >>> 24) == 0) row[x] = 0;
                        }
                    }
                    clean.setRGB(0, y, width, 1, row, 0, width);
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (var output = new MemoryCacheImageOutputStream(bytes)) {
                    if (!ImageIO.write(clean, "png", output)) {
                        throw new IOException("이미지를 처리할 수 없습니다");
                    }
                }
                if (bytes.size() > MAX_OUTPUT_BYTES) {
                    throw new IllegalArgumentException("처리된 이미지 크기가 허용 범위를 벗어났습니다");
                }
                return bytes.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }
}
