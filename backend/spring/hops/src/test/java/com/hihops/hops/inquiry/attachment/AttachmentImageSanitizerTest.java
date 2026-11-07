package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class AttachmentImageSanitizerTest {
    @Test
    void 가린_영역을_유지하며_png로_다시_인코딩한다() throws IOException {
        BufferedImage original = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        original.setRGB(0, 0, Color.BLACK.getRGB());
        original.setRGB(1, 1, Color.WHITE.getRGB());
        byte[] output = AttachmentImageSanitizer.sanitize(encode(original, "png"));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(decoded.getRGB(0, 0)).isEqualTo(Color.BLACK.getRGB());
        assertThat(decoded.getRGB(1, 1)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void jpeg를_png로_변환한다() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        byte[] output = AttachmentImageSanitizer.sanitize(encode(image, "jpeg"));
        assertThat(output).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(ImageIO.read(new ByteArrayInputStream(output)).getWidth()).isEqualTo(4);
    }

    @Test
    void 완전히_투명한_픽셀에_남은_색상_정보를_제거한다() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x00123456);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(
                AttachmentImageSanitizer.sanitize(encode(image, "png"))));
        assertThat(decoded.getRGB(0, 0)).isZero();
    }

    @Test
    void 이미지의_텍스트_메타데이터를_보존하지_않는다() throws IOException {
        byte[] source = encode(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png");
        byte[] metadata = "Comment\0test-private-metadata".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream withMetadata = new ByteArrayOutputStream();
        withMetadata.write(source, 0, 33); // PNG signature와 IHDR 뒤에 유효한 tEXt 청크를 넣는다.
        try (var data = new java.io.DataOutputStream(withMetadata)) {
            data.writeInt(metadata.length);
            byte[] kind = "tEXt".getBytes(StandardCharsets.US_ASCII);
            data.write(kind);
            data.write(metadata);
            CRC32 crc = new CRC32();
            crc.update(kind);
            crc.update(metadata);
            data.writeInt((int) crc.getValue());
            data.write(source, 33, source.length - 33);
        }
        byte[] input = withMetadata.toByteArray();
        assertThat(new String(input, StandardCharsets.ISO_8859_1)).contains("test-private-metadata");
        byte[] output = AttachmentImageSanitizer.sanitize(input);
        assertThat(new String(output, StandardCharsets.ISO_8859_1)).doesNotContain("test-private-metadata");
        assertThat(ImageIO.read(new ByteArrayInputStream(output))).isNotNull();
    }

    @Test
    void 빈_파일과_허위_이미지와_초과_크기를_거부한다() {
        assertThatThrownBy(() -> AttachmentImageSanitizer.sanitize(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttachmentImageSanitizer.sanitize(new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttachmentImageSanitizer.sanitize(new byte[5 * 1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 너무_큰_이미지_크기를_디코딩_전에_거부한다() throws IOException {
        BufferedImage image = new BufferedImage(8193, 1, BufferedImage.TYPE_INT_RGB);
        byte[] input = encode(image, "png");
        assertThatThrownBy(() -> AttachmentImageSanitizer.sanitize(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, bytes)).isTrue();
        return bytes.toByteArray();
    }
}
