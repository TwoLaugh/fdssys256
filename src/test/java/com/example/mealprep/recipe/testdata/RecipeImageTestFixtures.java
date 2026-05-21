package com.example.mealprep.recipe.testdata;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * Recipe-02a test fixture: in-memory image byte arrays for {@code RecipeImageControllerIT} and
 * {@code LocalFilesystemImageStoreTest}.
 *
 * <p>JPEG / PNG are produced via {@link ImageIO} so the bytes carry real magic bytes that Tika
 * recognises; the {@code nonImage()} fixture is a 1-KiB blob of zeros that has neither image magic
 * nor any other recognisable signature — Tika reports it as {@code application/octet-stream}, which
 * is correctly rejected by the MIME allow-list.
 *
 * <p>WebP is supplied as a hand-rolled minimal RIFF container ({@code RIFF....WEBPVP8 }) — Tika's
 * default detectors recognise the {@code RIFF}/{@code WEBP} magic bytes without needing an actual
 * VP8-encoded payload (Tika is a sniffer, not a decoder).
 */
public final class RecipeImageTestFixtures {

  private RecipeImageTestFixtures() {}

  /** Returns a small valid JPEG. */
  public static byte[] jpeg() {
    return encodeImage("jpg");
  }

  /** Returns a small valid PNG. */
  public static byte[] png() {
    return encodeImage("png");
  }

  /**
   * Returns a minimal RIFF/WebP container with the magic bytes Tika sniffs. The container is
   * deliberately tiny; production code only ever reads the first 512 bytes for the magic-byte
   * probe.
   */
  public static byte[] webp() {
    // RIFF.... (size little-endian, ignored by Tika's sniffer) WEBPVP8 ....
    byte[] header =
        new byte[] {
          'R',
          'I',
          'F',
          'F',
          0x20,
          0x00,
          0x00,
          0x00, // chunk size: 32 (filler)
          'W',
          'E',
          'B',
          'P',
          'V',
          'P',
          '8',
          ' ',
          0x10,
          0x00,
          0x00,
          0x00, // VP8 chunk size: 16
          // Filler bytes to round out a recognisable shell.
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00
        };
    return header;
  }

  /**
   * Returns 1 KiB of zeros — Tika should detect this as {@code application/octet-stream} (or
   * similar non-image), which the MIME allow-list rejects with 415.
   */
  public static byte[] nonImage() {
    return new byte[1024];
  }

  /**
   * A second-distinct JPEG with different pixel content so re-uploading the alternate JPEG to the
   * same recipe produces a different content-hash → different storage key (idempotency negative
   * case).
   */
  public static byte[] alternateJpeg() {
    return encodeImage("jpg", 0x33, 0x66, 0x99);
  }

  // ---------------- helpers ----------------

  private static byte[] encodeImage(String formatName) {
    return encodeImage(formatName, 0x99, 0x99, 0x99);
  }

  private static byte[] encodeImage(String formatName, int r, int g, int b) {
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    int rgb = ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        image.setRGB(x, y, rgb);
      }
    }
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (!ImageIO.write(image, formatName, out)) {
        throw new IllegalStateException("ImageIO writer unavailable for format: " + formatName);
      }
      return out.toByteArray();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
